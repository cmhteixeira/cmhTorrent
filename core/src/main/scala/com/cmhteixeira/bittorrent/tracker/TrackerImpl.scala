package com.cmhteixeira.bittorrent.tracker

import cats.implicits.catsSyntaxFlatten
import com.cmhteixeira.bittorrent.tracker.TrackerImpl.{Config, limitConnectionId, retries}
import com.cmhteixeira.bittorrent.{InfoHash, PeerId, UdpSocket}
import org.slf4j.LoggerFactory

import java.net.{DatagramPacket, DatagramSocket, InetAddress, InetSocketAddress}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ScheduledExecutorService, ScheduledFuture, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import scala.util.{Failure, Success, Try}

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
    }).foreach {
      case (udpSocket, result) => result.onComplete(registerStateAndSend(torrent.infoHash, udpSocket, _))(mainExecutor)
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
      case (Some(state4Torrent), Success(inetSocket)) if inetSocket.isUnresolved =>
        val newState = currentState + (infoHash -> state4Torrent.newTrackerUnresolved(udpSocket))
        if (!state.compareAndSet(currentState, newState)) registerStateAndSend(infoHash, udpSocket, a)
        else logger.warn(s"Tracker '${udpSocket.hostName}:${udpSocket.port}' could not be resolved.")
      case (Some(state4Torrent), Success(inetSocket)) =>
        val socketAddressWithNoHostname = // we don't want association with a particular hostname
          new InetSocketAddress(InetAddress.getByAddress(inetSocket.getAddress.getAddress), inetSocket.getPort)
        val conn = createConnect(infoHash, socketAddressWithNoHostname, 0)
        state4Torrent.newTrackerSent(socketAddressWithNoHostname, conn) match {
          case Some(newStateForTorrent) =>
            val newState = currentState + (infoHash -> newStateForTorrent)
            if (!state.compareAndSet(currentState, newState)) {
              logger.warn(
                s"Cancelling connect check task for: $infoHash, $socketAddressWithNoHostname, ${conn.txnId}, ${conn.n}"
              )
              conn.checkTask.cancel(true)
              registerStateAndSend(infoHash, udpSocket, a)
            } else {
              logger.info(s"Added tracker '$socketAddressWithNoHostname'.")
              sendConnect(conn.txnId, socketAddressWithNoHostname)
            }
          case None =>
            conn.checkTask.cancel(true)
            logger.warn(s"Repeated tracker $inetSocket")
        }

      case (None, Failure(exception)) => logger.warn("Weird....", exception)
      case (None, Success(_)) => logger.warn("Weird....")
    }
  }

  private def createConnect(infoHash: InfoHash, tracker: InetSocketAddress, iterNum: Int): ConnectSent = {
    val txnId = txnIdGen.txnId()
    val promise = Promise[Unit]()
    logger.info(
      s"Scheduling task to check Connect response for $infoHash from $tracker for iter $iterNum. ${retries(iterNum)}"
    )
    promise.future.onComplete {
      case Failure(exception) => logger.error(s"This should be impossible.", exception) // todo: Fix this?
      case Success(_) => sendAnnounce(infoHash, tracker)
    }(mainExecutor)
    val checkTask = scheduler.schedule(
      new Runnable { def run(): Unit = checkConnectResponse(infoHash, tracker, txnId) },
      retries(iterNum),
      TimeUnit.SECONDS
    )
    ConnectSent(txnId, promise, checkTask, iterNum)
  }

  private def sendConnect(transactionId: Int, trackerSocket: InetSocketAddress): Unit = {
    val payload = ConnectRequest(transactionId).serialize
    Try(socket.send(new DatagramPacket(payload, payload.length, trackerSocket))) match {
      case Failure(exception) =>
        logger.warn(
          s"Sending Connect to '${trackerSocket.getAddress}' with transaction id '$transactionId'.",
          exception
        )
      case Success(_) =>
        logger.info(s"Sent Connect to '$trackerSocket' with transaction id '$transactionId'.")
    }
  }

  private def sendAnnounce(infoHash: InfoHash, connectionId: Long, txnId: Int, tracker: InetSocketAddress): Unit = {
    val announceRequest = AnnounceRequest(
      connectionId = connectionId,
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
      case Failure(exception) =>
        logger.warn(
          s"Sending Announce to '$tracker' for '$infoHash' within connection id '$connectionId' with txnId '$txnId'.",
          exception
        )
      case Success(_) =>
        logger.info(
          s"Sent Announce to '$tracker' for '$infoHash' within connection id '$connectionId' and txnId '$txnId'."
        )
    }
  }

  @tailrec
  private def sendAnnounce(infoHash: InfoHash, tracker: InetSocketAddress): Unit = {
    val currentState = state.get()
    currentState.get(infoHash) match {
      case Some(Submitted) =>
        logger.warn(s"This state is impossible. 'Submitted' for $infoHash when sending announce to '$tracker'.")
      case Some(tiers @ Tiers(_, _)) =>
        tiers.get(tracker) match {
          case Some(ConnectReceived(conId, timestamp)) =>
            val iter = 0
            val txnId = txnIdGen.txnId()
            val checkTask = scheduleAnnounceResponseCheck(infoHash, tracker, txnId, retries(iter))
            val announce = AnnounceSent(txnId, conId, timestamp, checkTask, iter)
            val newState = currentState + (infoHash -> tiers.updateEntry(tracker, announce))
            if (!state.compareAndSet(currentState, newState)) {
              checkTask.cancel(true)
              sendAnnounce(infoHash, tracker)
            } else sendAnnounce(infoHash, conId, txnId, tracker)
          case Some(state) => logger.warn(s"Sending Announce to '$tracker' for '$infoHash', but state is '$state'")
          case None => logger.warn(s"Sending Announce to '$tracker' for '$infoHash' but tracker not registered.")
        }
      case None => logger.warn(s"Sending Announce to '$tracker' but '$infoHash' not registered.")
    }
  }

  private def scheduleAnnounceResponseCheck(
      infoHash: InfoHash,
      trackerSocket: InetSocketAddress,
      txnId: Int,
      delay: Int
  ): ScheduledFuture[_] =
    scheduler.schedule(
      new Runnable { def run(): Unit = checkAnnounceResponse(infoHash, trackerSocket, txnId) },
      delay,
      TimeUnit.SECONDS
    )

  @tailrec
  private def checkConnectResponse(infoHash: InfoHash, tracker: InetSocketAddress, txnId: Int): Unit = {
    val currentState = state.get()
    currentState.get(infoHash) match {
      case Some(Submitted) =>
        logger.warn(s"Impossible state. 'Submitted' for $infoHash when checking connect response to '$tracker'.")
      case Some(tiers @ Tiers(_, _)) =>
        tiers.get(tracker) match {
          case Some(ConnectSent(thisTxnId, _, _, n)) if thisTxnId == txnId =>
            val iter = math.min(8, n + 1)
            val connectSent = createConnect(infoHash, tracker, iter) // side effects
            val newState = currentState + (infoHash -> tiers.updateEntry(tracker, connectSent))
            if (!state.compareAndSet(currentState, newState)) {
              logger.warn(
                s"Cancelling connect check task for: $infoHash, $tracker, ${txnId}"
              )
              connectSent.checkTask.cancel(true) // better way?
              checkConnectResponse(infoHash, tracker, txnId)
            } else {
              logger.info(
                s"Connect response from '$tracker' for '$infoHash' with txnId '$txnId' (iter=$n) not received."
              )
              sendConnect(connectSent.txnId, tracker)
            }
          case Some(state) =>
            logger.warn(
              s"Checking Connect response from '$tracker' for '$infoHash' and '$txnId', but state is now '$state'."
            )
          case None =>
            logger.warn(
              s"Checking Connect response from '$tracker' for '$infoHash' and '$txnId' but tracker nonexistent."
            )
        }
      case None =>
        logger.warn(s"Checking Connect response from '$tracker' for '$infoHash' and '$txnId' but info-hash nonexistent")
    }
  }

  @tailrec
  private def checkAnnounceResponse(infoHash: InfoHash, tracker: InetSocketAddress, txnId: Int): Unit = {
    val currentState = state.get()
    val msgPrefix = s"Checking Announce response from '$tracker' for '$infoHash' with txnId '$txnId'."
    currentState.get(infoHash) match {
      case Some(Submitted) =>
        logger.warn(
          s"Impossible state. 'Submitted' for $infoHash when checking announce response (txnId=$txnId) to '$tracker'."
        )
      case Some(tiers @ Tiers(_, _)) =>
        tiers.get(tracker) match {
          case Some(AnnounceSent(thisTxnId, connectionId, timestampConnId, _, n)) if thisTxnId == txnId =>
            val newTxnId = txnIdGen.txnId()
            if (limitConnectionId(timestampConnId)) {
              val connectSent = createConnect(infoHash, tracker, 0)
              val newState = currentState + (infoHash -> tiers.updateEntry(tracker, connectSent))
              if (!state.compareAndSet(currentState, newState)) {
                connectSent.checkTask.cancel(true)
                checkAnnounceResponse(infoHash, tracker, txnId)
              } else {
                logger.info(
                  s"$msgPrefix. Not received. Sending Connect with txnId '$newTxnId' as connectionId '$connectionId' is stale."
                )
                sendConnect(connectSent.txnId, tracker)
              }
            } else {
              val iter = n + 1
              val newTxnId = txnIdGen.txnId()
              val checkTask = scheduleAnnounceResponseCheck(infoHash, tracker, newTxnId, retries(iter))
              val announceSent = AnnounceSent(newTxnId, connectionId, timestampConnId, checkTask, iter)

              val newState = currentState + (infoHash -> tiers.updateEntry(tracker, announceSent))
              if (!state.compareAndSet(currentState, newState)) {
                logger.warn(
                  s"Cancelling announce check task for: $infoHash, $tracker, ${newTxnId}. Iter: $iter"
                )
                checkTask.cancel(true)
                checkAnnounceResponse(infoHash, tracker, txnId)
              } else {
                logger.info(
                  s"No Announce response from '$tracker' for '$infoHash' with txnId '$txnId'. Sending Announce with txnId '$newTxnId' and re-using connectionId '$connectionId'."
                )
                sendAnnounce(infoHash, connectionId, newTxnId, tracker)
              }
            }
          case Some(state) => logger.warn(s"$msgPrefix but state is now '$state'.")
          case None => logger.warn(s"$msgPrefix but tracker not registered.")
        }
      case None => logger.warn(s"$msgPrefix but infoHash nonexistent.")
    }
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
      case Submitted => Tracker.Statistics(Tracker.Summary(0, 0, 0, 0, 0, 0), Map.empty)
      case Tiers(underlying, _) =>
        underlying
          .foldLeft[(Set[InetSocketAddress], Tracker.Statistics)]((Set.empty, TrackerImpl.emptyStatistics)) {
            case ((acc, stats: Tracker.Statistics), (tracker, _: ConnectSent)) => (acc, stats.addConnectSent(tracker))
            case ((acc, stats: Tracker.Statistics), (tracker, _: ConnectReceived)) =>
              (acc, stats.addConnectReceived(tracker))
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

  private val emptyStatistics = Tracker.Statistics(Tracker.Summary(0, 0, 0, 0, 0, 0), Map.empty)
  case class Config(port: Int, peerId: PeerId, key: Int)

  private val retries: Int => Int = n => (15 * math.pow(2, n)).toInt

  private val limitConnectionId: Long => Boolean = timestamp => (System.nanoTime() - timestamp) / 1000000000L > 60

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
