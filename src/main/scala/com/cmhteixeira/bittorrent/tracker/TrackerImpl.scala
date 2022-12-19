package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent.{InfoHash, PeerId}
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
    state: AtomicReference[Map[InfoHash, Tiers[State]]],
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService,
    config: Config,
    txnIdGenerator: TransactionIdGenerator
) extends Tracker {
  private val logger = LoggerFactory.getLogger(getClass)
  mainExecutor.execute(ReaderThread(socket, state))

  @tailrec
  def submit(torrent: Torrent): Unit = {
    val currentState = state.get()
    if (currentState.exists { case (hash, _) => hash == torrent.infoHash }) ()
    else {
      val entry = Tiers.start(txnIdGenerator, torrent)
      if (!state.compareAndSet(currentState, currentState + (torrent.infoHash -> entry))) submit(torrent)
      else entry.toList.foreach { case (socket, state) => sendConnect(torrent.infoHash, socket, state) }
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
      case Some(tiers) =>
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
      case Some(tiers) =>
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
      case Some(tiers) =>
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
      case Some(tiers) =>
        tiers.toList.flatMap {
          case (socket, AnnounceReceived(_, _, peers)) => peers
          case _ => Nil
        }.distinct
      case _ => List.empty
    }
}

object TrackerImpl {

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
      new AtomicReference[Map[InfoHash, Tiers[State]]](Map.empty),
      mainExecutor,
      scheduler,
      config,
      transactionIdGenerator
    )
}
