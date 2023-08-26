package com.cmhteixeira.bittorrent.tracker.core

import cats.implicits.catsSyntaxFlatten
import com.cmhteixeira.bittorrent.tracker.TrackerState._
import com.cmhteixeira.bittorrent.tracker._
import com.cmhteixeira.bittorrent.tracker.core.TrackerImpl.{Config, Connection, connAgeSec, limitConnectionId}
import com.cmhteixeira.bittorrent.{InfoHash, PeerId, UdpSocket}
import com.cmhteixeira.streams.publishers.CmhPublisher
import org.reactivestreams.{Subscriber, Subscription}
import org.slf4j.LoggerFactory

import java.net.{DatagramPacket, DatagramSocket, InetAddress, InetSocketAddress}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{LinkedBlockingQueue, ScheduledExecutorService, TimeUnit}
import scala.concurrent.Future.failed
import scala.concurrent._
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

// TODO Problems:
// 1. If we have the same tracker being used for multiple torrents, we don't re-use connection.
private[tracker] final class TrackerImpl private (
    socket: DatagramSocket,
    state: AtomicReference[Map[InfoHash, Map[InetSocketAddress, TrackerState]]],
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService,
    config: Config,
    txnIdGen: TransactionIdGenerator,
    subscribers: AtomicReference[Map[InfoHash, Set[Subscriber[InetSocketAddress]]]],
    queue: LinkedBlockingQueue[(InfoHash, InetSocketAddress)],
    udpReceive: CmhPublisher[TrackerResponse]
) extends Tracker {
  private val logger = LoggerFactory.getLogger("Tracker")

  udpReceive.subscribe(new UdpSubscriber)
  private class UdpSubscriber extends Subscriber[TrackerResponse] {
    override def onSubscribe(s: Subscription): Unit = s.request(Long.MaxValue)
    override def onNext(t: TrackerResponse): Unit =
      t match {
        case TrackerResponse.ConnectReceived(origin, msg, timestamp) => processConnect(origin, msg, timestamp)
        case TrackerResponse.AnnounceReceived(origin, msg) => processAnnounce(origin, msg)
      }
    override def onError(t: Throwable): Unit = ???
    override def onComplete(): Unit = ???

    private def processConnect(origin: InetSocketAddress, connectResponse: ConnectResponse, timestamp: Long): Unit = {
      val currentState = state.get()
      val ConnectResponse(txnId, connectId) = connectResponse
      currentState.toList.flatMap { case (hash, underlying) =>
        underlying.get(origin) match {
          case Some(conSent @ ConnectSent(txnId, _)) if txnId == connectResponse.transactionId => List(hash -> conSent)
          case _ => List.empty
        }
      } match {
        case Nil => logger.warn(s"Received possible Connect response from '$origin', but no state across all torrents.")
        case (infoHash, ConnectSent(_, channel)) :: Nil =>
          logger.info(s"Matched Connect response: Torrent=$infoHash,tracker=$origin,txdId=$txnId,connId=$connectId")
          channel.trySuccess((connectResponse, timestamp))
        case xs =>
          logger.warn(
            s"Connect response (txdId=${connectResponse.transactionId}) matches more than 1 torrent: [${xs.map(_._1).mkString(", ")}]."
          )
      }
    }

    private def processAnnounce(origin: InetSocketAddress, announceResponse: AnnounceResponse): Unit = {
      val currentState = state.get()
      val AnnounceResponse(_, _, _, _, _, peers) = announceResponse
      currentState.flatMap { case (infoHash, state4Torrent) =>
        state4Torrent
          .map { case (address, state) => address -> state }
          .toList
          .collectFirst {
            case (thisTrackerSocket, announceSent @ AnnounceSent(txnId, _, _))
                if thisTrackerSocket == origin && txnId == announceResponse.transactionId =>
              announceSent
          }
          .map(a => (infoHash, state4Torrent, a))
      }.toList match {
        case Nil =>
          logger.warn(s"Received possible Announce response from '$origin', but no state across all torrents.")
        case all @ (one :: two :: other) => logger.warn(s"Omg... this shouldn't be happening")
        case (infoHash, tiers, AnnounceSent(txnId, _, channel)) :: Nil if txnId == announceResponse.transactionId =>
          logger.info(s"Announce response from '$origin' for '$infoHash' with txnId '$txnId': ${peers.size} peers.")
          channel.trySuccess(announceResponse)
        case (infoHash, tiers, AnnounceSent(txnId, _, channel)) :: Nil => logger.warn("Bla blabla")
      }
    }
  }

  new Thread(
    new Runnable {
      override def run(): Unit = {
        while (true) {
          val (infoHash, peer) = queue.take()
          subscribers.get().get(infoHash) match {
            case Some(subx) =>
              subx.foreach { s =>
                Try(s.onNext(peer)) match {
                  case Failure(exception) => logger.info(s"Error calling onNext with '$peer' for $infoHash", exception)
                  case Success(_) => logger.trace(s"Pushed onNext with '$peer' for '$infoHash'")
                }
              }
            case None => logger.info(s"No subscribers for '$infoHash'")
          }
        }
      }
    },
    "tracker-subscriber-push"
  ).start()

  override def submit(torrent: Torrent): CmhPublisher[InetSocketAddress] = new CmhPublisher[InetSocketAddress] {
    def subscribeCast(s: Subscriber[InetSocketAddress]): Unit = {
      val currentState = subscribers.get()
      val currentSubscribers = currentState.getOrElse(torrent.infoHash, Set.empty)
      if (currentSubscribers.contains(s)) {
        s.onError(new IllegalStateException("Already subscribed."))
      } else {
        val newState = currentState + (torrent.infoHash -> (currentSubscribers + s))
        if (!subscribers.compareAndSet(currentState, newState)) subscribe(s)
        else s.onSubscribe(new TrackerSubscription(s, torrent))
      }
    }

    override def subscribe(s: Subscriber[_ >: InetSocketAddress]): Unit = {
      if (s == null) throw new NullPointerException("Subscriber cannot be null.")
      subscribeCast(s.asInstanceOf[Subscriber[InetSocketAddress]])
    }
  }

  private class TrackerSubscription(s: Subscriber[InetSocketAddress], torrent: Torrent) extends Subscription {
    override def request(n: Long): Unit = {
      if (!subscribers.get().get(torrent.infoHash).exists(_.contains(s))) ()
      else if (n <= 0) s.onError(new IllegalArgumentException(s"Non-positive requests are illegal. Provided: $n"))
      else if (n <= Long.MaxValue - 1)
        s.onError(new IllegalArgumentException(s"Demand must be unbounded. Request 'Long.MaxValue - 1' or higher"))
      else submit2(torrent)
    }
    override def cancel(): Unit = {
      val currentState = subscribers.get()
      currentState.get(torrent.infoHash) match {
        case Some(currentSubxs) =>
          if (
            !subscribers.compareAndSet(
              currentState,
              currentState + (torrent.infoHash -> currentSubxs.filterNot(_ == s))
            )
          )
            cancel()
          else logger.info(s"Removed subscription $s for ${torrent.infoHash}")
        case None => logger.info("Cancelling")
      }
    }
  }

  def submit2(torrent: Torrent): Unit = {
    val currentState = state.get()
    if (currentState.exists { case (hash, _) => hash == torrent.infoHash })
      logger.info(s"Submitting torrent ${torrent.infoHash} but it already exists.")
    else if (!state.compareAndSet(currentState, currentState + (torrent.infoHash -> Map.empty))) submit(torrent)
    else {
      implicit val ec: ExecutionContext = mainExecutor
      logger.info(s"Submitted torrent ${torrent.infoHash}.")
      addTorrent(torrent).foreach(_.onComplete {
        case Failure(e) => logger.warn(s"Problem with a tracker when submitting torrent '${torrent.infoHash}'.", e)
        case Success(sA) => logger.warn(s"Infinite loop for tracker '$sA on torrent '${torrent.infoHash}' finished.")
      })
    }
  }

  private def addTorrent(torrent: Torrent)(implicit ec: ExecutionContext): List[Future[InetSocketAddress]] = {
    def resolveHost(trackerSocket: UdpSocket): Future[InetSocketAddress] =
      Future.unit.flatMap(_ =>
        Future.fromTry(blocking { Try(new InetSocketAddress(trackerSocket.hostName, trackerSocket.port)) })
      )

    (torrent.announceList match {
      case Some(udpHostnameAndPort) =>
        udpHostnameAndPort.flatten.toList.distinct.map(hostAndPort => hostAndPort -> resolveHost(hostAndPort))
      case None => List(torrent.announce -> resolveHost(torrent.announce))
    }).map { case (tracker, result) =>
      for {
        soAddress <- result
        noHostSoAddress <-
          if (soAddress.isUnresolved)
            failed(new IllegalArgumentException(s"Tracker '${tracker.hostName}:${tracker.port}' couldn't be resolved."))
          else
            Future.successful(
              new InetSocketAddress(InetAddress.getByAddress(soAddress.getAddress.getAddress), soAddress.getPort)
            )
        _ <- run(torrent.infoHash, noHostSoAddress)
      } yield noHostSoAddress
    }
  }

  private def sendConnectDownTheWire(txnId: Int, tracker: InetSocketAddress): Unit = {
    val payload = ConnectRequest(txnId).serialize
    Try(socket.send(new DatagramPacket(payload, payload.length, tracker))) match {
      case Failure(err) => logger.warn(s"Sending Connect to '${tracker.getAddress}' with transaction id '$txnId'.", err)
      case Success(_) => logger.info(s"Sent Connect to '$tracker' with transaction id '$txnId'.")
    }
  }

  private def sendAnnounceDownTheWire(infoHash: InfoHash, conId: Long, txnId: Int, tracker: InetSocketAddress): Unit = {
    val announceRequest = AnnounceRequest(
      connectionId = conId,
      transactionId = txnId,
      action = AnnounceRequest.Announce,
      infoHash = infoHash,
      peerId = config.peerId,
      downloaded = 0,
      left = 0,
      uploaded = 0,
      event = AnnounceRequest.Started, // parametrized this
      ipAddress = 0,
      key = config.key,
      numWanted = -1,
      port = 8081 // parametrize this
    )
    val payload = announceRequest.serialize
    Try(socket.send(new DatagramPacket(payload, payload.length, tracker))) match {
      case Failure(error) =>
        logger.warn(s"Sending Announce to '$tracker' for '$infoHash' with connId '$conId' and txnId '$txnId'.", error)
      case Success(_) =>
        logger.info(s"Sent Announce to '$tracker' for '$infoHash' with connId '$conId' and txnId '$txnId'.")
    }
  }

  private def run(infoHash: InfoHash, tracker: InetSocketAddress)(implicit ec: ExecutionContext): Future[Unit] =
    (for {
      (connectRes, timestampConn) <- connect(infoHash, tracker)
      connection = Connection(connectRes.connectionId, timestampConn)
      announceRes <- announce(infoHash, tracker, connection)
      _ <- setAndReannounce(infoHash, tracker, announceRes.peers.toSet, connection)
    } yield ()) recoverWith { case timeout: TimeoutException =>
      logger.warn(s"Obtaining peers from '$tracker' from '$infoHash'. Retrying ...", timeout)
      run(infoHash, tracker)
    }

  private def connect(infoHash: InfoHash, tracker: InetSocketAddress)(implicit
      ec: ExecutionContext
  ): Future[(ConnectResponse, Long)] = {
    def inner(n: Int): Future[(ConnectResponse, Long)] = {
      val currentState = state.get()
      currentState.get(infoHash) match {
        case Some(trackers) =>
          val txdId = txnIdGen.txnId()
          val promise = Promise[(ConnectResponse, Long)]()
          val newState4Torrent = trackers + (tracker -> ConnectSent(txdId, promise))
          val newState = currentState + (infoHash -> newState4Torrent)
          if (!state.compareAndSet(currentState, newState)) inner(n)
          else {
            sendConnectDownTheWire(txdId, tracker)
            timeout(promise.future, TrackerImpl.retries(math.min(8, n)).seconds).recoverWith {
              case _: TimeoutException => inner(n + 1) // todo: stackoverflow risk
            }
          }
        case None => Future.failed(new IllegalStateException(s"Connecting to $tracker but '$infoHash' doesn't exist."))
      }
    }
    inner(0)
  }

  private def announce(
      infoHash: InfoHash,
      tracker: InetSocketAddress,
      connection: Connection
  )(implicit ec: ExecutionContext): Future[AnnounceResponse] = {
    def inner(n: Int): Future[AnnounceResponse] = {
      val currentState = state.get()
      currentState.get(infoHash) match {
        case Some(trackers) =>
          val txdId = txnIdGen.txnId()
          val promise = Promise[AnnounceResponse]()
          val newState4Torrent = trackers + (tracker -> AnnounceSent(txdId, connection.id, promise))
          val newState = currentState + (infoHash -> newState4Torrent)
          if (!state.compareAndSet(currentState, newState)) inner(n)
          else {
            sendAnnounceDownTheWire(infoHash, connection.id, txdId, tracker)
            timeout(promise.future, TrackerImpl.retries(math.min(8, n)).seconds).recoverWith {
              case _: TimeoutException =>
                if (limitConnectionId(connection.timestamp))
                  Future.failed(
                    new TimeoutException(
                      s"Connection to $tracker (${connAgeSec(connection.timestamp)} s) expired before announce received."
                    )
                  )
                else inner(n + 1)
            }
          }
        case _ => Future.failed(new IllegalStateException(s"Announcing to $tracker for $infoHash but no such torrent."))
      }
    }
    inner(0)
  }

  private def setAndReannounce(
      infoHash: InfoHash,
      tracker: InetSocketAddress,
      newPeers: Set[InetSocketAddress],
      connection: Connection
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val currentState = state.get()
    currentState.get(infoHash) match {
      case Some(trackers) =>
        val newState4Torrent = trackers + (tracker -> AnnounceReceived(connection.timestamp, newPeers.size))
        val newState = currentState + (infoHash -> newState4Torrent)
        if (!state.compareAndSet(currentState, newState)) setAndReannounce(infoHash, tracker, newPeers, connection)
        else {
          newPeers.foreach(i => queue.offer(infoHash, i)) // todo: does not block
          for {
            _ <- after(Success(()), config.announceTimeInterval)
            _ <-
              if (limitConnectionId(connection.timestamp))
                failed(new TimeoutException(s"Connection to $tracker (${connAgeSec(connection.timestamp)} s) expired."))
              else Future.unit
            _ = logger.info(s"Re-announcing to $tracker for $infoHash. Previous peers obtained: ${newPeers.size}")
            announceRes <- announce(infoHash, tracker, connection)
            _ <- setAndReannounce(infoHash, tracker, announceRes.peers.toSet, connection)
          } yield ()
        }
      case _ =>
        Future.failed(new IllegalStateException(s"Re-announcing to $tracker for $infoHash but no such torrent."))
    }
  }

  private def timeout[A](fut: Future[A], timeout: FiniteDuration)(implicit ec: ExecutionContext): Future[A] = {
    val promise = Promise[A]()
    scheduler.schedule(
      new Runnable { override def run(): Unit = promise.tryFailure(new TimeoutException(s"Timeout after $timeout.")) },
      timeout.toMillis,
      TimeUnit.MILLISECONDS
    )
    fut.onComplete(promise.tryComplete)
    promise.future
  }

  private def after[A](futValue: Try[A], delay: FiniteDuration): Future[A] = {
    val promise = Promise[A]()
    scheduler.schedule(
      new Runnable { override def run(): Unit = promise.tryComplete(futValue) },
      delay.toMillis,
      TimeUnit.MILLISECONDS
    )
    promise.future
  }

  def statistics(peerToState: Map[InetSocketAddress, TrackerState]): Tracker.Statistics =
    peerToState
      .foldLeft[Tracker.Statistics](TrackerImpl.emptyStatistics) {
        case (stats, (tracker, _: ConnectSent)) => stats.addConnectSent(tracker)
        case (stats, (tracker, _: AnnounceSent)) => stats.addAnnounceSent(tracker)
        case (stats, (tracker, AnnounceReceived(_, numPeers))) => stats.addAnnounceReceived(tracker, numPeers)
      }
      .setNumberPeers(peerToState.size)

  override def statistics: Map[InfoHash, Tracker.Statistics] =
    state.get().map { case (infoHash, trackerState) => infoHash -> statistics(trackerState) }

}

object TrackerImpl {

  private case class Connection(id: Long, timestamp: Long)

  private val emptyStatistics = Tracker.Statistics(Tracker.Summary(0, 0, 0, 0, 0), Map.empty)
  case class Config(port: Int, peerId: PeerId, key: Int, announceTimeInterval: FiniteDuration)

  private val retries: Int => Int = n => (15 * math.pow(2, n)).toInt

  private val limitConnectionId: Long => Boolean = timestamp => connAgeSec(timestamp) > 60

  private val connAgeSec: Long => Long = timestamp => (System.nanoTime() - timestamp) / 1000000000L

  def apply(
      mainExecutor: ExecutionContext,
      scheduler: ScheduledExecutorService,
      transactionIdGenerator: TransactionIdGenerator,
      config: Config
  ): TrackerImpl = {
    val socket = new DatagramSocket(config.port)

    new TrackerImpl(
      socket = socket,
      state = new AtomicReference[Map[InfoHash, Map[InetSocketAddress, TrackerState]]](Map.empty),
      mainExecutor,
      scheduler,
      config,
      transactionIdGenerator,
      new AtomicReference[Map[InfoHash, Set[Subscriber[InetSocketAddress]]]](Map.empty),
      new LinkedBlockingQueue[(InfoHash, InetSocketAddress)](),
      TrackerResponseStream(socket)
    )
  }
}
