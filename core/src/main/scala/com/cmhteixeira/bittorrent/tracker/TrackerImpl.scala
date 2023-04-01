package com.cmhteixeira.bittorrent.tracker

import cats.implicits.catsSyntaxFlatten
import com.cmhteixeira.bittorrent.tracker.TrackerImpl.{Config, connAgeSec, limitConnectionId}
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
  def submit(torrent: Torrent): Unit = {
    val currentState = state.get()
    if (currentState.exists { case (hash, _) => hash == torrent.infoHash })
      logger.info(s"Submitted torrent ${torrent.infoHash} but it already exists.")
    else if (!state.compareAndSet(currentState, currentState + (torrent.infoHash -> Submitted))) submit(torrent)
    else {
      logger.info(s"Submitted torrent ${torrent.infoHash}.")
      Future.unit.map(_ => addTorrent(torrent))(mainExecutor)
    }
  }

  private def addTorrent(
      torrent: Torrent
  ): Unit = {
    def resolveHost(trackerSocket: UdpSocket): Future[InetSocketAddress] =
      Future.unit.flatMap(_ =>
        Future.fromTry(blocking { Try(new InetSocketAddress(trackerSocket.hostName, trackerSocket.port)) })
      )(mainExecutor)

    (torrent.announceList match {
      case Some(udpHostnameAndPort) =>
        udpHostnameAndPort.flatten.toList.distinct.map(hostAndPort => hostAndPort -> resolveHost(hostAndPort))
      case None => List(torrent.announce -> resolveHost(torrent.announce))
    }).foreach { case (tracker, result) =>
      result
        .flatMap(socketAddress =>
          if (socketAddress.isUnresolved)
            failed(new IllegalArgumentException(s"Tracker '${tracker.hostName}:${tracker.port}' couldn't be resolved."))
          else Future.successful(socketAddress)
        )(mainExecutor)
        .onComplete(registerStateAndSend(torrent.infoHash, tracker, _))(mainExecutor)
    }
  }

  @tailrec
  private def registerStateAndSend(infoHash: InfoHash, udpSocket: UdpSocket, a: Try[InetSocketAddress]): Unit = {
    val currentState = state.get()
    (currentState.get(infoHash), a) match {
      case (Some(state4Torrent), Failure(exception)) =>
        val newState = currentState + (infoHash -> state4Torrent.newTrackerUnresolved(udpSocket))
        if (!state.compareAndSet(currentState, newState)) registerStateAndSend(infoHash, udpSocket, a)
        else logger.warn(s"Tracker '${udpSocket.hostName}:${udpSocket.port}' could not be resolved.", exception)
      case (Some(state4Torrent), Success(inetSocket)) =>
        val socketAddressWithNoHostname = // we don't want association with a particular hostname
          new InetSocketAddress(InetAddress.getByAddress(inetSocket.getAddress.getAddress), inetSocket.getPort)
        obtainPeers(infoHash, socketAddressWithNoHostname)(mainExecutor)

      case (None, Failure(exception)) => logger.warn("Weird....", exception)
      case (None, Success(_)) => logger.warn("Weird....")
    }
  }

  private def sendConnectDownTheWire(txnId: Int, tracker: InetSocketAddress): Unit = {
    val payload = ConnectRequest(txnId).serialize
    Try(socket.send(new DatagramPacket(payload, payload.length, tracker))) match {
      case Failure(exception) =>
        logger.warn(s"Sending Connect to '${tracker.getAddress}' with transaction id '$txnId'.", exception)
      case Success(_) =>
        logger.info(s"Sent Connect to '$tracker' with transaction id '$txnId'.")
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

  private def obtainPeers(infoHash: InfoHash, tracker: InetSocketAddress)(implicit ec: ExecutionContext): Unit =
    (for {
      (connectRes, timestampConn) <- connect(infoHash, tracker)
      announceRes <- announce(infoHash, tracker, connectRes, timestampConn)
    } yield announceRes) onComplete {
      case Success(announceRes) => setNewPeers(infoHash, tracker, announceRes)
      case Failure(timeout: TimeoutException) =>
        logger.warn(s"Obtaining peers from '$tracker' from '$infoHash'. Retrying ...", timeout)
        obtainPeers(infoHash, tracker)
      case Failure(otherError) => logger.warn(s"Obtaining peers from '$tracker' from '$infoHash'.", otherError)
    }

  @tailrec
  private def setNewPeers(infoHash: InfoHash, tracker: InetSocketAddress, announceResponse: AnnounceResponse): Unit = {
    val currentState = state.get()
    val AnnounceResponse(_, _, _, leech, seeds, peers) = announceResponse
    currentState.get(infoHash) match {
      case Some(Submitted) => logger.warn("Foobar")
      case Some(tiers @ Tiers(_, _)) =>
        val newState = currentState + (infoHash -> tiers.updateEntry(tracker, AnnounceReceived(leech, seeds, peers)))
        if (!state.compareAndSet(currentState, newState)) setNewPeers(infoHash, tracker, announceResponse)
        else logger.info(s"Added ${peers.size} for $infoHash from $tracker.")
      case None => logger.warn("foo Bar")
    }
  }

  private def connect(infoHash: InfoHash, tracker: InetSocketAddress): Future[(ConnectResponse, Long)] = {
    def inner(n: Int): Future[(ConnectResponse, Long)] = {
      val currentState = state.get()
      val txdId = txnIdGen.txnId()
      currentState.get(infoHash) match {
        case Some(state4Torrent) =>
          val promise = Promise[(ConnectResponse, Long)]()
          val newState4Torrent = state4Torrent.newTrackerSent(tracker, ConnectSent(txdId, promise))
          val newState = currentState + (infoHash -> newState4Torrent)
          if (!state.compareAndSet(currentState, newState)) inner(n)
          else {
            sendConnectDownTheWire(txdId, tracker)
            timeout(promise.future, TrackerImpl.retries(math.min(8, n)).seconds).recoverWith {
              case _: TimeoutException => inner(n + 1) // todo: stackoverflow risk
            }(mainExecutor)
          }
        case None =>
          Future.failed(new IllegalStateException(s"Connecting to $tracker but torrent $infoHash doesn't exist."))
      }
    }
    inner(0)
  }

  private def announce(
      infoHash: InfoHash,
      tracker: InetSocketAddress,
      conResponse: ConnectResponse,
      timestamp: Long
  ): Future[AnnounceResponse] = {
    def inner(n: Int): Future[AnnounceResponse] = {
      val currentState = state.get()
      currentState.get(infoHash) match {
        case Some(Submitted) =>
          Future.failed(new IllegalStateException(s"Announcing to $tracker for $infoHash, but no such torrent."))
        case Some(tiers @ Tiers(_, _)) =>
          val txdId = txnIdGen.txnId()
          val promise = Promise[AnnounceResponse]()
          val announce = AnnounceSent(txdId, conResponse.connectionId, promise)
          val newState = currentState + (infoHash -> tiers.updateEntry(tracker, announce))
          if (!state.compareAndSet(currentState, newState)) inner(n)
          else {
            sendAnnounceDownTheWire(infoHash, conResponse.connectionId, txdId, tracker)
            timeout(promise.future, TrackerImpl.retries(math.min(8, n)).seconds).recoverWith {
              case _: TimeoutException =>
                if (limitConnectionId(timestamp))
                  Future.failed(
                    new TimeoutException(
                      s"Connection to $tracker (${connAgeSec(timestamp)} s) expired before announce received."
                    )
                  )
                else inner(n + 1)
            }(mainExecutor)
          }
        case None =>
          Future.failed(new IllegalStateException(s"Announcing to $tracker for $infoHash, but no such torrent."))
      }
    }
    inner(0)
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

  override def peers(infoHash: InfoHash): List[InetSocketAddress] =
    state.get().get(infoHash) match {
      case Some(Submitted) => List.empty
      case Some(Tiers(underlying, _)) =>
        underlying.flatMap {
          case (_, AnnounceReceived(_, _, peers)) => peers
          case _ => Nil
        }.toList
      case _ => List.empty
    }

  def statistics(trackerState: State): Tracker.Statistics =
    trackerState match {
      case Submitted => Tracker.Statistics(Tracker.Summary(0, 0, 0, 0, 0), Map.empty)
      case Tiers(underlying, _) =>
        underlying
          .foldLeft[(Set[InetSocketAddress], Tracker.Statistics)]((Set.empty, TrackerImpl.emptyStatistics)) {
            case ((acc, stats: Tracker.Statistics), (tracker, _: ConnectSent)) => (acc, stats.addConnectSent(tracker))
            case ((acc, stats: Tracker.Statistics), (tracker, _: AnnounceSent)) => (acc, stats.addAnnounceSent(tracker))
            case ((acc, stats: Tracker.Statistics), (tracker, AnnounceReceived(_, _, peers))) =>
              val distinct = acc ++ peers.toSet
              (distinct, stats.addAnnounceReceived(tracker, peers.size, distinct.size))
          }
          ._2
    }

  override def statistics: Map[InfoHash, Tracker.Statistics] =
    state.get().map { case (infoHash, trackerState) => infoHash -> statistics(trackerState) }

}

object TrackerImpl {

  private val emptyStatistics = Tracker.Statistics(Tracker.Summary(0, 0, 0, 0, 0), Map.empty)
  case class Config(port: Int, peerId: PeerId, key: Int)

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
    mainExecutor.execute(ReaderThread(socket, sharedState))

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
