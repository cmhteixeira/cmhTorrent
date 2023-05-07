package com.cmhteixeira.bittorrent.tracker

import cats.implicits.catsSyntaxFlatten
import com.cmhteixeira.bittorrent.tracker.TrackerImpl.{Config, Connection, connAgeSec, limitConnectionId}
import com.cmhteixeira.bittorrent.{InfoHash, PeerId, UdpSocket}
import org.slf4j.LoggerFactory

import java.net.{DatagramPacket, DatagramSocket, InetAddress, InetSocketAddress}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException, blocking}
import scala.util.{Failure, Success, Try}
import scala.concurrent.Future.failed
import TrackerState._
// TODO Problems:
// 1. If we have the same tracker being used for multiple torrents, we don't re-use connection.
private[tracker] final class TrackerImpl private (
    socket: DatagramSocket,
    state: AtomicReference[Map[InfoHash, State]],
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService,
    config: Config,
    txnIdGen: TransactionIdGenerator
) extends Tracker {
  private val logger = LoggerFactory.getLogger("TrackerImpl")

  @tailrec
  override def submit(torrent: Torrent): Unit = {
    val currentState = state.get()
    if (currentState.exists { case (hash, _) => hash == torrent.infoHash })
      logger.info(s"Submitting torrent ${torrent.infoHash} but it already exists.")
    else if (!state.compareAndSet(currentState, currentState + (torrent.infoHash -> State.empty))) submit(torrent)
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
      event = AnnounceRequest.Event.Started, // parametrized this
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
        case Some(State(peers, trackers)) =>
          val txdId = txnIdGen.txnId()
          val promise = Promise[(ConnectResponse, Long)]()
          val newState4Torrent = State(peers, trackers = trackers + (tracker -> ConnectSent(txdId, promise)))
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
        case Some(state4Torrent @ State(_, _)) =>
          val txdId = txnIdGen.txnId()
          val promise = Promise[AnnounceResponse]()
          val announce = AnnounceSent(txdId, connection.id, promise)
          val newState = currentState + (infoHash -> state4Torrent.updateEntry(tracker, announce))
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
      peers: Set[InetSocketAddress],
      connection: Connection
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val currentState = state.get()
    currentState.get(infoHash) match {
      case Some(state4Torrent @ State(_, _)) =>
        val newEntry4Tracker = state4Torrent.updateEntry(tracker, AnnounceReceived(connection.timestamp, peers.size))
        val newState =
          currentState + (infoHash -> newEntry4Tracker.copy(peers = newEntry4Tracker.peers ++ peers))
        if (!state.compareAndSet(currentState, newState)) setAndReannounce(infoHash, tracker, peers, connection)
        else
          for {
            _ <- after(Success(()), config.announceTimeInterval)
            _ <-
              if (limitConnectionId(connection.timestamp))
                failed(new TimeoutException(s"Connection to $tracker (${connAgeSec(connection.timestamp)} s) expired."))
              else Future.unit
            _ = logger.info(s"Re-announcing to $tracker for $infoHash. Previous peers obtained: ${peers.size}")
            announceRes <- announce(infoHash, tracker, connection)
            _ <- setAndReannounce(infoHash, tracker, announceRes.peers.toSet, connection)
          } yield ()
      case _ =>
        Future.failed(new IllegalStateException(s"Re-announcing to $tracker for $infoHash but no such torrent."))
    }
  }

  private def timeout[A](fut: Future[A], timeout: FiniteDuration): Future[A] = {
    val promise = Promise[A]()
    scheduler.schedule(
      new Runnable { override def run(): Unit = promise.tryFailure(new TimeoutException(s"Timeout after $timeout.")) },
      timeout.toMillis,
      TimeUnit.MILLISECONDS
    )
    fut.onComplete(promise.tryComplete)(mainExecutor)
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

  override def peers(infoHash: InfoHash): Set[InetSocketAddress] =
    state.get().get(infoHash) match {
      case Some(State(peers, _)) => peers
      case _ => Set.empty
    }

  def statistics(trackerState: State): Tracker.Statistics =
    trackerState match {
      case State(allPeers, underlying) =>
        underlying
          .foldLeft[Tracker.Statistics](TrackerImpl.emptyStatistics) {
            case (stats, (tracker, _: ConnectSent)) => stats.addConnectSent(tracker)
            case (stats, (tracker, _: AnnounceSent)) => stats.addAnnounceSent(tracker)
            case (stats, (tracker, AnnounceReceived(_, numPeers))) => stats.addAnnounceReceived(tracker, numPeers)
          }
          .setNumberPeers(allPeers.size)
    }

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
    val sharedState = new AtomicReference[Map[InfoHash, State]](Map.empty)
    val thread = new Thread(ReaderThread(socket, sharedState), "tracker-udp-socket-reads")
    thread.start()

    new TrackerImpl(
      socket = socket,
      state = sharedState,
      mainExecutor,
      scheduler,
      config,
      transactionIdGenerator
    )
  }
}
