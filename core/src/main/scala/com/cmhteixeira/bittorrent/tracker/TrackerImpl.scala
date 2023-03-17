package com.cmhteixeira.bittorrent.tracker

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.{InfoHash, PeerId, UdpSocket}
import com.cmhteixeira.bittorrent.tracker.TrackerImpl.{Config, limitConnectionId, retries}
import org.slf4j.LoggerFactory

import java.net.{DatagramPacket, DatagramSocket, InetSocketAddress}
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Failure, Success, Try}

private[tracker] final class TrackerImpl private (
    socket: DatagramSocket,
    state: AtomicReference[Map[InfoHash, Foo]],
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService,
    config: Config,
    txnIdGenerator: TransactionIdGenerator
) extends Tracker {
  private val logger = LoggerFactory.getLogger("TrackerImpl")
  mainExecutor.execute(ReaderThread(socket, state))

  def submit(torrent: Torrent): Unit = {
    val currentState = state.get()
    if (currentState.exists { case (hash, _) => hash == torrent.infoHash }) ()
    else {
      if (!state.compareAndSet(currentState, currentState + (torrent.infoHash -> Submitted))) submit(torrent)
      else {
        logger.info(s"Submitted torrent '${torrent.infoHash}'")
        val entry = Tiers.start(txnIdGenerator, torrent)
        entry.toList.foreach {
          case (udpSocket, connectSent) =>
            scheduler.submit(addTracker(torrent.infoHash, udpSocket, connectSent))
        }
      }
    }
  }

  private def addTracker(
      infoHash: InfoHash,
      trackerSocket: UdpSocket,
      connectSent: ConnectSent
  ): Runnable =
    new Runnable {

      @tailrec
      def monkey(trackerSocket: InetSocketAddress): Unit = {
        val currentState = state.get()
        currentState.get(infoHash) match {
          case Some(Submitted) =>
            val newState = currentState + (infoHash -> Tiers(NonEmptyList.one(trackerSocket -> connectSent)))
            if (!state.compareAndSet(currentState, newState)) monkey(trackerSocket)
            else {
              logger.info(s"Added tracker '$trackerSocket'.")
              sendConnect(infoHash, trackerSocket, connectSent)
            }
          case Some(Tiers(underlying)) =>
            val newState = currentState + (infoHash -> Tiers(underlying :+ (trackerSocket -> connectSent)))
            if (!state.compareAndSet(currentState, newState)) monkey(trackerSocket)
            else {
              logger.info(s"Added tracker '$trackerSocket'.")
              sendConnect(infoHash, trackerSocket, connectSent)
            }
          case None => logger.warn("Weird....")
        }
      }

      def run(): Unit =
        Try(new InetSocketAddress(trackerSocket.hostName, trackerSocket.port)) match {
          case Failure(exception) => logger.warn(s"Failed to add tracker '$trackerSocket'.", exception)
          case Success(inetSocketAddress) => monkey(inetSocketAddress)
        }
    }

  private def sendConnect(transactionId: Int, trackerSocket: InetSocketAddress): Try[Unit] = {
    val payload = ConnectRequest(transactionId).serialize
    Try(socket.send(new DatagramPacket(payload, payload.length, trackerSocket)))
  }

  private def sendAnnounce(
      infoHash: InfoHash,
      connectionId: Long,
      txnId: Int,
      trackerSocket: InetSocketAddress
  ): Try[Unit] = {
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

    logger.info(s"Sending announce for infoHash $infoHash.")
    val payload = announceRequest.serialize
    Try(socket.send(new DatagramPacket(payload, payload.length, trackerSocket)))
  }

  private def sendConnect(infoHash: InfoHash, tracker: InetSocketAddress, connectSent: ConnectSent): Unit = {
    val ConnectSent(txnId, channel, tries) = connectSent
    val delayInSeconds = retries(tries)
    sendConnect(txnId, tracker) match {
      case Failure(exception) =>
        logger.warn(s"Sending Connect to '$tracker' for '$infoHash' with transaction id '$txnId'.", exception)
        scheduleConnectResponseCheck(infoHash, tracker, txnId, delayInSeconds)

      case Success(_) =>
        logger.info(s"Sent Connect to '$tracker' for '$infoHash' with transaction id '$txnId'.")
        doWhenConnectResponseReceived(channel, infoHash, tracker)
        scheduleConnectResponseCheck(infoHash, tracker, txnId, delayInSeconds)
    }
  }

  private def scheduleConnectResponseCheck(
      infoHash: InfoHash,
      tracker: InetSocketAddress,
      txnId: Int,
      delay: Int
  ): Unit =
    scheduler.schedule(
      new Runnable { def run(): Unit = checkConnectResponse(infoHash, tracker, txnId) },
      delay,
      TimeUnit.SECONDS
    )

  private def doWhenConnectResponseReceived(
      promise: Promise[Unit],
      infoHash: InfoHash,
      trackerSocket: InetSocketAddress
  ): Unit =
    promise.future.onComplete {
      case Failure(exception) => logger.error(s"This should be impossible.", exception) // todo: Fix this?
      case Success(_) => sendAnnounce(infoHash, trackerSocket)
    }(mainExecutor)

  @tailrec
  private def sendAnnounce(infoHash: InfoHash, tracker: InetSocketAddress): Unit = {
    val currentState = state.get()
    currentState.get(infoHash) match {
      case Some(Submitted) => logger.warn("Can't send announce.")
      case Some(tiers @ Tiers(_)) =>
        tiers.get(tracker) match {
          case Some(ConnectReceived(conId, timestamp)) =>
            val announce = AnnounceSent(txnIdGenerator.newTransactionId(), conId, timestamp, 0)
            val newState = currentState + (infoHash -> tiers.updateEntry(tracker, announce))
            if (!state.compareAndSet(currentState, newState)) sendAnnounce(infoHash, tracker)
            else sendAnnounc(infoHash, announce, tracker)
          case Some(state) => logger.info(s"Sending Announce to '$tracker' for '$infoHash', but state is '$state'")
          case None => logger.info(s"Sending Announce to '$tracker' for '$infoHash' but tracker not registered.")
        }
      case None => logger.warn(s"Sending Announce to '$tracker' but '$infoHash' not registered.")
    }
  }

  private def sendAnnounc(infoHash: InfoHash, announce: AnnounceSent, tracker: InetSocketAddress): Unit = {
    val AnnounceSent(txnId, connectionId, _, n) = announce
    sendAnnounce(infoHash, connectionId, txnId, tracker) match {
      case Failure(exception) =>
        logger.warn(
          s"Sending Announce to '$tracker' for '$infoHash' within connection id '$connectionId' with txnId '$txnId'.",
          exception
        )
        scheduleAnnounceResponseCheck(infoHash, tracker, txnId, retries(n))
      case Success(_) =>
        logger.info(
          s"Sent Announce to '$tracker' for '$infoHash' within connection id '$connectionId' and txnId '$txnId'."
        )
        scheduleAnnounceResponseCheck(infoHash, tracker, txnId, retries(n))
    }
  }

  private def scheduleAnnounceResponseCheck(
      infoHash: InfoHash,
      trackerSocket: InetSocketAddress,
      txnId: Int,
      delay: Int
  ): Unit =
    scheduler.schedule(
      new Runnable { def run(): Unit = checkAnnounceResponse(infoHash, trackerSocket, txnId) },
      delay,
      TimeUnit.SECONDS
    )

  @tailrec
  private def checkConnectResponse(infoHash: InfoHash, tracker: InetSocketAddress, txnId: Int): Unit = {
    val currentState = state.get()
    currentState.get(infoHash) match {
      case Some(Submitted) => logger.warn("This is impossible?")
      case Some(tiers @ Tiers(underlying)) =>
        tiers.get(tracker) match {
          case Some(ConnectSent(thisTxnId, _, n)) if thisTxnId == txnId =>
            val connectSent = ConnectSent(txnIdGenerator.newTransactionId(), Promise[Unit](), n + 1)
            val newState = currentState + (infoHash -> tiers.updateEntry(tracker, connectSent))
            if (!state.compareAndSet(currentState, newState)) checkConnectResponse(infoHash, tracker, txnId)
            else {
              logger.warn(s"Connect response from '$tracker' for '$infoHash' with txnId '$txnId' not received.")
              sendConnect(infoHash, tracker, connectSent)
            }
          case Some(state) =>
            logger.info(
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
      case Some(Submitted) => logger.warn("This is impossible?")
      case Some(tiers @ Tiers(underlying)) =>
        tiers.get(tracker) match {
          case Some(announce @ AnnounceSent(thisTxnId, connectionId, timestampConnectionId, n)) if thisTxnId == txnId =>
            val newTxnId = txnIdGenerator.newTransactionId()
            if (limitConnectionId(timestampConnectionId)) {
              val connectSent = ConnectSent(newTxnId, Promise[Unit](), 0)
              val newState = currentState + (infoHash -> tiers.updateEntry(tracker, connectSent))
              if (!state.compareAndSet(currentState, newState)) checkAnnounceResponse(infoHash, tracker, txnId)
              else {
                logger.info(
                  s"$msgPrefix. Not received. Sending Connect with txnId '$newTxnId' as connectionId '$connectionId' is stale."
                )
                sendConnect(infoHash, tracker, connectSent)
              }
            } else {
              val announceSent = announce.copy(txnId = txnIdGenerator.newTransactionId(), n = n + 1)
              val newState = currentState + (infoHash -> tiers.updateEntry(tracker, announceSent))
              if (!state.compareAndSet(currentState, newState)) checkAnnounceResponse(infoHash, tracker, txnId)
              else {
                logger.info(
                  s"No Announce response from '$tracker' for '$infoHash' with txnId '$txnId'. Sending Announce with txnId '$newTxnId' and re-using connectionId '$connectionId'."
                )
                sendAnnounc(infoHash, announceSent, tracker)
              }
            }
          case Some(state) => logger.info(s"$msgPrefix but state is now '$state'.")
          case None => logger.warn(s"$msgPrefix but tracker not registered.")
        }
      case None => logger.warn(s"$msgPrefix but infoHash nonexistent.")
    }
  }

  override def peers(infoHash: InfoHash): List[InetSocketAddress] =
    state.get().get(infoHash) match {
      case Some(Submitted) => List.empty
      case Some(tiers @ Tiers(underlying)) =>
        tiers.toList.flatMap {
          case (socket, AnnounceReceived(_, _, peers)) => peers
          case _ => Nil
        }.distinct
      case _ => List.empty
    }

  def statistics2(foo: Foo): Tracker.Statistics =
    foo match {
      case Submitted => Tracker.Statistics(Tracker.Summary(0, 0, 0, 0, 0, 0), Map.empty)
      case Tiers(underlying) =>
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
    state.get().map { case (infoHash, foo) => infoHash -> statistics2(foo) }

}

object TrackerImpl {

  private val emptyStatistics = Tracker.Statistics(Tracker.Summary(0, 0, 0, 0, 0, 0), Map.empty)
  case class Config(port: Int, peerId: PeerId, key: Int)

  private val retries: Int => Int = n => 15 * (2 ^ n)

  private val limitConnectionId: Long => Boolean = timestamp => (System.nanoTime() - timestamp) / 1000000000L > 60

  def apply(
      mainExecutor: ExecutionContext,
      scheduler: ScheduledExecutorService,
      transactionIdGenerator: TransactionIdGenerator,
      config: Config
  ): TrackerImpl =
    new TrackerImpl(
      new DatagramSocket(config.port),
      new AtomicReference[Map[InfoHash, Foo]](Map.empty),
      mainExecutor,
      scheduler,
      config,
      transactionIdGenerator
    )
}
