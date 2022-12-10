package com.cmhteixeira.bittorrent.tracker

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.{InfoHash, PeerId}
import com.cmhteixeira.bittorrent.tracker.Tracker.Peer
import com.cmhteixeira.bittorrent.tracker.TrackerImpl.Config
import org.slf4j.LoggerFactory

import java.net.{DatagramPacket, DatagramSocket, InetSocketAddress}
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Failure, Success, Try}

private[tracker] class TrackerImpl private (
    socket: DatagramSocket,
    trackers: AtomicReference[Map[InfoHash, Tiers]],
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService,
    config: Config,
    txnIdGenerator: TransactionIdGenerator
) extends Tracker {
  private val logger = LoggerFactory.getLogger(getClass)
  mainExecutor.execute(ReaderThread(socket, trackers))
  def submit(i: Tracker.Torrent): Unit = firstSubmission(i, i.infoHash)

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

  @tailrec
  private def firstSubmission(torrent: Tracker.Torrent, infoHash: InfoHash): Unit = {
    val currentState = trackers.get()
    if (currentState.exists { case (hash, _) => hash == infoHash }) ()
    else {
      val txnId = txnIdGenerator.newTransactionId()
      val connectReceivedChannel = Promise[Unit]()
      val connectSent = ConnectSent(txnId, connectReceivedChannel)
      val tiers =
        torrent.announceList.fold(Tiers.fromSinglePeer(torrent.announce, connectSent))(announceList =>
          Tiers.fromMultipleTiers(announceList, connectSent)
        )

      if (!trackers.compareAndSet(currentState, currentState + (infoHash -> tiers))) firstSubmission(torrent, infoHash)
      else {
        val (trackerSocket, _) = tiers.currentState
        sendConnect(txnId, trackerSocket) match {
          case Failure(exception) => // does this guarantee the tracker never received it?
            logger.warn(
              s"Sending Connect to '$trackerSocket' for '$infoHash' with transaction id '$txnId'.",
              exception
            )
            checkConnectResponse(infoHash, txnId)
          case Success(_) =>
            logger.info(s"Sent Connect to '$trackerSocket' for '$infoHash' with transaction id '$txnId'.")
            doWhenConnectResponseReceived(connectReceivedChannel, infoHash)
            scheduleConnectResponseCheck(infoHash, txnId)
        }
      }
    }
  }

  private def scheduleConnectResponseCheck(infoHash: InfoHash, txnId: Int): Unit =
    scheduler.schedule(
      new Runnable { def run(): Unit = checkConnectResponse(infoHash, txnId) },
      15,
      TimeUnit.SECONDS
    )

  private def doWhenConnectResponseReceived(promise: Promise[Unit], infoHash: InfoHash): Unit =
    promise.future.onComplete {
      case Failure(exception) => logger.error(s"This should be impossible.", exception) // todo: Fix this?
      case Success(_) => sendAnnounce(infoHash)
    }(mainExecutor)

  @tailrec
  private def sendAnnounce(infoHash: InfoHash): Unit = {
    val currentState = trackers.get()
    currentState.get(infoHash) match {
      case Some(tiers @ Tiers(_, currentTier @ CurrentTier((socket, ConnectReceived(connectId, _)), _), _)) =>
        val txnId = txnIdGenerator.newTransactionId()
        val newStateForHash = tiers.copy(current = currentTier.copy(current = (socket, AnnounceSent(txnId))))

        if (!trackers.compareAndSet(currentState, currentState + (infoHash -> newStateForHash))) sendAnnounce(infoHash)
        else {
          sendAnnounce(infoHash, connectId, txnId, socket) match {
            case Failure(exception) =>
              logger.warn(
                s"Sending Announce to '$socket' for '$infoHash' within connection id '$connectId' with txnId '$txnId'.",
                exception
              )
              checkAnnounceResponse(infoHash, txnId)
            case Success(_) =>
              logger.info(
                s"Sent Announce to '$socket' for '$infoHash' within connection id '$connectId' and txnId '$txnId'"
              )
              scheduleAnnounceResponseCheck(infoHash, txnId)
          }
        }

      case Some(Tiers(_, CurrentTier((_, state), _), _)) =>
        logger.warn(s"Was going to send announce from triggering, but state is now '$state'. Doing nothing ...")
      case None => logger.warn("What does this mean?")
    }
  }

  private def scheduleAnnounceResponseCheck(infoHash: InfoHash, txnId: Int): Unit =
    scheduler.schedule(
      new Runnable { def run(): Unit = checkAnnounceResponse(infoHash, txnId) },
      15,
      TimeUnit.SECONDS
    )

  private def checkConnectResponse(infoHash: InfoHash, txnId: Int): Unit = {
    val currentState = trackers.get()
    currentState.get(infoHash) match {
      case Some(Tiers(previousTiers, CurrentTier((socket, ConnectSent(theTxnId, _)), nextOnTier), nextTiers))
          if theTxnId == txnId =>
        val untried = nextOnTier.collect { case (address, false) => address }
        val channel = Promise[Unit]()
        val newTxnId = txnIdGenerator.newTransactionId()
        val newState = ConnectSent(newTxnId, channel)
        ((untried, nextTiers) match {
          case (Nil, Nil) => None
          case (head :: rest, _) =>
            val withHeadFiltered = nextOnTier.filterNot { case (a, _) => a == head }
            val currentTier = CurrentTier((head, newState), (socket, true) +: withHeadFiltered)
            Some(Tiers(previousTiers, currentTier, nextTiers))
          case (Nil, Tier(trackers) :: rest) =>
            val currentTier = Tier(NonEmptyList(socket, nextOnTier.map(_._1)))
            val currentSocket = (trackers.head, newState)
            val restNewCurrentTier = trackers.tail.map((_, false))
            Some(Tiers(previousTiers :+ currentTier, CurrentTier(currentSocket, restNewCurrentTier), rest))
        }) match {
          case Some(newStateForHash) =>
            val newState = currentState + (infoHash -> newStateForHash)
            if (!trackers.compareAndSet(currentState, newState)) checkConnectResponse(infoHash, txnId)
            else {
              val (trackerSocket, _) = newStateForHash.currentState
              logger.warn(s"Connect response '$socket' for txnId '$txnId' not received. New tracker '$trackerSocket'.")

              logger.info(s"Sending Connect to '$trackerSocket' with txnId '$newTxnId'.")
              sendConnect(newTxnId, trackerSocket) match {
                case Failure(exception) => // does this guarantee the tracker never received it?
                  logger.warn(
                    s"Sending Connect to '$trackerSocket' for '$infoHash' with txnId '$newTxnId'.",
                    exception
                  )
                  checkConnectResponse(infoHash, newTxnId)
                case Success(_) =>
                  logger.info(s"Sent Connect to '$trackerSocket' for '$infoHash' with txnId '$newTxnId'.")
                  doWhenConnectResponseReceived(channel, infoHash)
                  scheduleConnectResponseCheck(infoHash, newTxnId)
              }
            }
          case None => logger.warn("What the heck to do ??? Try them all again ?")
        }

      case Some(Tiers(_, CurrentTier((socket, ConnectSent(theTxnId, _)), _), _)) if theTxnId != txnId =>
        logger.warn(s"Connect response from '$socket' for txnId '$txnId' not received. Actual txnId is '$theTxnId'.")
      case Some(Tiers(_, CurrentTier((so, s), _), _)) =>
        logger.info(s"Checking Connect response from '$so' for '$infoHash' with txnId '$txnId'. State is '$s'.")
      case None =>
        logger.warn(s"Checking Connect response for '$infoHash' and '$txnId' but torrent doesn't exist.")
    }
  }

  private def checkAnnounceResponse(infoHash: InfoHash, txnId: Int): Unit = {
    val currentState = trackers.get()

    currentState.get(infoHash) match {
      case Some(Tiers(previousTiers, CurrentTier((socket, AnnounceSent(theTxnId)), nextOnTier), nextTiers))
          if theTxnId == txnId =>
        val untried = nextOnTier.collect { case (address, false) => address }
        val channel = Promise[Unit]()
        val newTxnId = txnIdGenerator.newTransactionId()
        val newState = ConnectSent(newTxnId, channel)
        ((untried, nextTiers) match {
          case (Nil, Nil) => None // what the heck to do
          case (head :: rest, _) =>
            val withHeadFiltered = nextOnTier.filterNot { case (a, _) => a == head }
            val currentTier = CurrentTier((head, newState), (socket, true) +: withHeadFiltered)
            Some(Tiers(previousTiers, currentTier, nextTiers))
          case (Nil, Tier(trackers) :: rest) =>
            val foo = Tier(NonEmptyList(socket, nextOnTier.map(_._1)))
            val currentSocket = (trackers.head, newState)
            val restCurrentTier = trackers.tail.map((_, false))
            Some(Tiers(previousTiers :+ foo, CurrentTier(currentSocket, restCurrentTier), rest))
        }) match {
          case Some(newStateForHash) =>
            val newState = currentState + ((infoHash, newStateForHash))
            if (!trackers.compareAndSet(currentState, newState)) checkConnectResponse(infoHash, txnId)
            else {
              val (trackerSocket, _) = newStateForHash.currentState
              logger.warn(
                s"Announce response from '$socket' for txnId '$txnId' not received. New tracker '$trackerSocket'."
              )

              logger.info(s"Sending Connect to '$trackerSocket'.")
              sendConnect(newTxnId, trackerSocket) match {
                case Failure(exception) => // does this guarantee the tracker never received it?
                  logger.warn(
                    s"Sending Connect to '$trackerSocket' for '$infoHash' with transaction id '$newTxnId'.",
                    exception
                  )
                  checkConnectResponse(infoHash, newTxnId)
                case Success(_) =>
                  logger.info(s"Sent Connect to '$socket' for '$infoHash' with transaction id '$newTxnId'")
                  doWhenConnectResponseReceived(channel, infoHash)
                  scheduleConnectResponseCheck(infoHash, newTxnId)
              }
            }
          case None => logger.warn("What the heck to do ??? Try them all again ?. Check announce.")
        }

      case Some(Tiers(_, CurrentTier((socket, AnnounceSent(theTxnId)), _), _)) if theTxnId == txnId =>
        logger.warn(
          s"Checking Announce response from '$socket' for txnId '$txnId': Status is now 'AnnounceSent' with different txnID. Very weird. Expected txnID: '$txnId'; actual: '$theTxnId'"
        )
      case Some(Tiers(_, CurrentTier((so, s), _), _)) =>
        logger.info(s"Checking Announce response from '$so' for '$infoHash' with txnId '$txnId'. State is '$s'.")
      case None =>
        logger.warn(s"Checking Announce response for '$infoHash' and '$txnId' but torrent doesn't exist.")
    }
  }

  override def peers(infoHash: InfoHash): List[Tracker.Peer] =
    trackers.get().get(infoHash) match {
      case Some(Tiers(_, CurrentTier((_, AnnounceReceived(_, _, peers)), _), _)) => peers.map(Peer)
      case _ => List.empty
    }
}

object TrackerImpl {

  case class Config(port: Int, peerId: PeerId, key: Int)

  def apply(
      mainExecutor: ExecutionContext,
      scheduler: ScheduledExecutorService,
      transactionIdGenerator: TransactionIdGenerator,
      config: Config
  ): TrackerImpl =
    new TrackerImpl(
      new DatagramSocket(config.port),
      new AtomicReference[Map[InfoHash, Tiers]](Map.empty),
      mainExecutor,
      scheduler,
      config,
      transactionIdGenerator
    )
}
