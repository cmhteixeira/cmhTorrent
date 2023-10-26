package com.cmhteixeira.bittorrent.tracker.impl

import cats.implicits.catsSyntaxFlatten
import com.cmhteixeira.bittorrent.tracker._
import com.cmhteixeira.bittorrent.tracker.impl.TrackerImpl.TrackerState._
import com.cmhteixeira.bittorrent.tracker.impl.TrackerImpl._
import com.cmhteixeira.bittorrent.{InfoHash, PeerId, Torrent, UdpSocket}
import org.slf4j.LoggerFactory
import java.net.{DatagramPacket, DatagramSocket, InetAddress, InetSocketAddress}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ScheduledExecutorService, ScheduledFuture, TimeUnit}
import scala.concurrent.Future.failed
import scala.concurrent._
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

// TODO Problems:
// 1. If we have the same tracker being used for multiple torrents, we don't re-use connection.
private final class TrackerImpl private (
    state: AtomicReference[State],
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService,
    config: Config,
    txnIdGen: TransactionIdGenerator
) extends Tracker
    with ReaderThread.Handler { thisTracker =>
  private val logger = LoggerFactory.getLogger("Tracker")

  override def onError(exception: Exception): Unit = logger.warn("TODO - onError", exception)

  override def onConnectReceived(
      connectResponse: ConnectResponse,
      origin: InetSocketAddress,
      timestampNanos: Long
  ): Unit = {
    val currentState = state.get()
    currentState match {
      case i: State.Shutdown => logger.warn(s"Ignoring connection response as state is '$i'")
      case i @ State.Ready => logger.warn(s"Ignoring connection response as state is '$i'")
      case State.Active(_, _, torrentToState) =>
        val ConnectResponse(txnId, connectId) = connectResponse
        torrentToState.toList.flatMap { case (hash, State4Torrent(_, trackerToState)) =>
          trackerToState.get(origin) match {
            case Some(conSent @ ConnectSent(txnId, _)) if txnId == connectResponse.transactionId =>
              List(hash -> conSent)
            case _ => List.empty
          }
        } match {
          case Nil =>
            logger.warn(s"Received possible Connect response from '$origin', but no state across all torrents.")
          case (infoHash, ConnectSent(_, channel)) :: Nil =>
            logger.info(s"Matched Connect response: Torrent=$infoHash,tracker=$origin,txdId=$txnId,connId=$connectId")
            channel.trySuccess((connectResponse, timestampNanos))
          case xs =>
            logger.warn(
              s"Connect response (txdId=${connectResponse.transactionId}) matches more than 1 torrent: [${xs.map(_._1).mkString(", ")}]."
            )
        }
    }
  }

  override def onAnnounceReceived(
      announceResponse: AnnounceResponse,
      origin: InetSocketAddress
  ): Unit = {
    val currentState = state.get()
    currentState match {
      case i: State.Shutdown => logger.warn(s"Ignoring connection response as state is '$i'")
      case i @ State.Ready => logger.warn(s"Ignoring connection response as state is '$i'")
      case State.Active(_, _, torrentToState) =>
        val AnnounceResponse(_, _, _, _, _, peers) = announceResponse
        torrentToState.flatMap { case (infoHash, state4Torrent @ State4Torrent(_, trackerToState)) =>
          trackerToState
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

  private def subscribe(torrent: Torrent, s: Tracker.Subscriber): Unit = {
    val currentState = state.get()
    logger.info(s"Subscribing to torrent '$torrent'. State is '$currentState'.")
    currentState match {
      case sD: State.Shutdown =>
        if (!state.compareAndSet(currentState, sD.add(torrent, s))) subscribe(torrent, s) else ()
      case State.Ready =>
        val newSocket = new DatagramSocket(config.port)
        val threadHandle =
          new Thread(ReaderThread(newSocket, thisTracker), s"TrackerListener#${UUID.randomUUID().toString.take(5)}")
        if (!state.compareAndSet(currentState, State.Ready.activate(torrent, s, threadHandle, newSocket)))
          subscribe(torrent, s)
        else {
          logger.info(s"Spawning reader thread: ${threadHandle.getName}")
          threadHandle.start()
          s.onSubscribe(new TrackerSubscription(s, torrent))
          addTorrent(torrent)(mainExecutor)
        }
      case active: State.Active =>
        active.get(torrent.infoHash) match {
          case Some(State4Torrent(subscribers, _)) =>
            if (!subscribers.contains(s)) {
              if (!state.compareAndSet(currentState, active.addSubscriber(torrent.infoHash, s))) subscribe(torrent, s)
              else
                s.onSubscribe(new TrackerSubscription(s, torrent)) // todo: calling argument before returning method.
            }
          case None =>
            if (!state.compareAndSet(currentState, active.addSubscriber(torrent.infoHash, s))) subscribe(torrent, s)
            else s.onSubscribe(new TrackerSubscription(s, torrent))
            addTorrent(torrent)(mainExecutor)
        }
    }
  }

  override def submit(torrent: Torrent): Tracker.Publisher = new Tracker.Publisher {
    override def subscribe(s: Tracker.Subscriber): Unit = thisTracker.subscribe(torrent, s)
  }

  private def cancel(torrent: Torrent, s: Tracker.Subscriber): Unit = {
    val currentState = state.get()
    currentState match {
      case i @ State.Ready => logger.warn(s"Cancelling subscription for '$torrent' when state is '$i'.")
      case i: State.Shutdown => logger.warn(s"Cancelling subscription for '$torrent' when state is '$i'.")
      case active @ State.Active((socket, thread), scheduledTasks, _) =>
        active.get(torrent.infoHash) match {
          case Some(state4Torrent @ State4Torrent(currentSubscribers, _)) =>
            val newSubs = currentSubscribers.filterNot(_ == s)
            val newState =
              if (newSubs.nonEmpty) active.update(torrent.infoHash, state4Torrent.setSubscriptions(newSubs))
              else {
                val maybeNewState = active.removeTorrent(torrent.infoHash)
                if (maybeNewState.torrentToState.nonEmpty) maybeNewState
                else State.Shutdown(Map.empty)
              }
            if (!state.compareAndSet(currentState, newState)) cancel(torrent, s)
            else
              newState match {
                case State.Shutdown(_) => releaseResources(scheduledTasks, socket, thread)
                case _ => ()
              }

          case None => logger.warn("TODO")
        }
    }
  }

  private class TrackerSubscription(s: Tracker.Subscriber, torrent: Torrent) extends Tracker.Subscription {
    override def cancel(): Unit = thisTracker.cancel(torrent, s)
  }

  private def releaseResources(
      scheduledTasks: Set[ScheduledFuture[_]],
      socket: DatagramSocket,
      threadHandle: Thread
  ): Unit = {
    mainExecutor.execute(() => {
      def loop(): Unit = {
        val currentState = state.get()
        currentState match {
          case State.Ready => logger.warn("This should be impossible.")
          case State.Active(_, _, _) => logger.warn("This should be impossible.")
          case State.Shutdown(newSubscribers) =>
            if (!state.compareAndSet(currentState, State.Ready)) loop()
            else
              newSubscribers.foreach { case (torrent, subx) =>
                subx.foreach { s =>
                  logger.info("TODO: resubscribing ...")
                  subscribe(torrent, s)
                }
              }
        }
      }

      logger.info(s"Cancelling listener thread: ${threadHandle.getName}")
      socket.close()
      threadHandle.join()
      logger.info("Listener thread cancelled.")

      logger.info(s"Cancelling ${scheduledTasks.size} scheduled tasks.")
      scheduledTasks.foreach(_.cancel(true))

      loop()
    })
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

  private def sendConnectDownTheWire(socket: DatagramSocket, txnId: Int, tracker: InetSocketAddress): Unit = {
    val payload = ConnectRequest(txnId).serialize
    Try(socket.send(new DatagramPacket(payload, payload.length, tracker))) match {
      case Failure(err) => logger.warn(s"Sending Connect to '${tracker.getAddress}' with transaction id '$txnId'.", err)
      case Success(_) => logger.info(s"Sent Connect to '$tracker' with transaction id '$txnId'.")
    }
  }

  private def sendAnnounceDownTheWire(
      socket: DatagramSocket,
      infoHash: InfoHash,
      conId: Long,
      txnId: Int,
      tracker: InetSocketAddress
  ): Unit = {
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
      currentState match {
        case i: State.Shutdown => Future.failed(new IllegalStateException(s"Connecting, but state is '$i'."))
        case i @ State.Ready => Future.failed(new IllegalStateException(s"Connecting, but state is '$i'."))
        case active @ State.Active((socket, _), _, _) =>
          active.get(infoHash) match {
            case Some(state4Torrent) =>
              val txdId = txnIdGen.txnId()
              val promise = Promise[(ConnectResponse, Long)]()
              val newState4Torrent = state4Torrent.update(tracker, ConnectSent(txdId, promise))
              if (!state.compareAndSet(currentState, active.update(infoHash, newState4Torrent))) inner(n)
              else {
                sendConnectDownTheWire(socket, txdId, tracker)
                timeout(promise.future, TrackerImpl.retries(math.min(8, n)).seconds).recoverWith {
                  case _: TimeoutException => inner(n + 1) // todo: stackoverflow risk
                }
              }
            case None =>
              Future.failed(new IllegalStateException(s"Connecting to $tracker but '$infoHash' doesn't exist."))
          }
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
      currentState match {
        case i: State.Shutdown => Future.failed(new IllegalStateException(s"Announcing, but state is '$i'."))
        case i @ State.Ready => Future.failed(new IllegalStateException(s"Announcing, but state is '$i'."))
        case active @ State.Active((socket, _), _, _) =>
          active.get(infoHash) match {
            case Some(state4Torrent @ State4Torrent(_, trackerToState)) =>
              val txdId = txnIdGen.txnId()
              val promise = Promise[AnnounceResponse]()
              val newTrackerToState = state4Torrent.copy(trackerToState =
                trackerToState + (tracker -> AnnounceSent(txdId, connection.id, promise))
              )
              if (!state.compareAndSet(currentState, active.update(infoHash, newTrackerToState))) inner(n)
              else {
                sendAnnounceDownTheWire(socket, infoHash, connection.id, txdId, tracker)
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
            case _ =>
              Future.failed(new IllegalStateException(s"Announcing to $tracker for $infoHash but no such torrent."))
          }
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
    currentState match {
      case i: State.Shutdown => Future.failed(new IllegalStateException(s"Reannouncing, but state is '$i'."))
      case i @ State.Ready => Future.failed(new IllegalStateException(s"Reannouncing, but state is '$i'."))
      case active: State.Active =>
        active.get(infoHash) match {
          case Some(state4Torrent @ State4Torrent(subx, trackerToState)) =>
            val newTrackerToState = state4Torrent.copy(trackerToState =
              trackerToState + (tracker -> AnnounceReceived(connection.timestamp, newPeers.size))
            )
            if (!state.compareAndSet(currentState, active.update(infoHash, newTrackerToState)))
              setAndReannounce(infoHash, tracker, newPeers, connection)
            else {
              newPeers.foreach(peerAddress => subx.foreach(_.onNext(peerAddress)))
              for {
                _ <- after(Success(()), config.announceTimeInterval)
                _ <-
                  if (limitConnectionId(connection.timestamp))
                    failed(
                      new TimeoutException(s"Connection to $tracker (${connAgeSec(connection.timestamp)} s) expired.")
                    )
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

  }

  private def timeout[A](fut: Future[A], timeout: FiniteDuration)(implicit ec: ExecutionContext): Future[A] = {
    val promise = Promise[A]()
    val scheduledTask = scheduler.schedule(
      new Runnable { override def run(): Unit = promise.tryFailure(new TimeoutException(s"Timeout after $timeout.")) },
      timeout.toMillis,
      TimeUnit.MILLISECONDS
    )
    state.get() match {
      case i @ (State.Shutdown(_) | State.Ready) =>
        scheduledTask.cancel(true)
        Future.failed(new IllegalStateException(s"Scheduling a timeout when state tracker is $i."))
      case active @ State.Active(_, scheduledTasks, _) =>
        if (!state.compareAndSet(active, active.copy(scheduledTasks = scheduledTasks + scheduledTask))) {
          scheduledTask.cancel(true)
          this.timeout(fut, timeout)
        } else {
          fut.onComplete(promise.tryComplete)
          promise.future
        }
    }
  }

  private def after[A](futValue: Try[A], delay: FiniteDuration): Future[A] = {
    val promise = Promise[A]()
    val scheduledTask = scheduler.schedule(
      new Runnable { override def run(): Unit = promise.tryComplete(futValue) },
      delay.toMillis,
      TimeUnit.MILLISECONDS
    )
    state.get() match {
      case i @ (State.Shutdown(_) | State.Ready) =>
        scheduledTask.cancel(true)
        Future.failed(new IllegalStateException(s"Scheduling a delay when state tracker is $i."))
      case active @ State.Active(_, scheduledTasks, _) =>
        if (!state.compareAndSet(active, active.copy(scheduledTasks = scheduledTasks + scheduledTask))) {
          scheduledTask.cancel(true)
          this.after(futValue, delay)
        } else promise.future
    }
  }

  private def statistics(peerToState: Map[InetSocketAddress, TrackerState]): Tracker.Statistics =
    peerToState
      .foldLeft[Tracker.Statistics](TrackerImpl.emptyStatistics) {
        case (stats, (tracker, _: ConnectSent)) => stats.addConnectSent(tracker)
        case (stats, (tracker, _: AnnounceSent)) => stats.addAnnounceSent(tracker)
        case (stats, (tracker, AnnounceReceived(_, numPeers))) => stats.addAnnounceReceived(tracker, numPeers)
      }
      .setNumberPeers(peerToState.size)

  override def statistics: Map[InfoHash, Tracker.Statistics] =
    state.get() match {
      case i: State.Shutdown => Map.empty
      case i @ State.Ready => Map.empty
      case State.Active(_, _, torrentToState) =>
        torrentToState.map { case (infoHash, State4Torrent(_, trackerToState)) =>
          infoHash -> statistics(trackerToState)
        }
    }

}

object TrackerImpl {

  private sealed trait State

  private object State {

    case class Shutdown(subscribers: Map[Torrent, Set[Tracker.Subscriber]]) extends State {
      def add(torrent: Torrent, s: Tracker.Subscriber): Shutdown = {
        Shutdown(subscribers + (subscribers.get(torrent) match {
          case Some(existingSubx) => torrent -> (existingSubx + s)
          case None => torrent -> Set(s)
        }))
      }
    }

    case object Ready extends State {
      def activate(torrent: Torrent, s: Tracker.Subscriber, threadHandle: Thread, socket: DatagramSocket): Active =
        Active((socket, threadHandle), Set.empty, Map(torrent.infoHash -> State4Torrent(Set(s), Map.empty)))
    }

    case class Active(
        threadAndSocket: (DatagramSocket, Thread),
        scheduledTasks: Set[ScheduledFuture[_]],
        torrentToState: Map[InfoHash, State4Torrent]
    ) extends State {
      def apply(infoHash: InfoHash): State4Torrent = torrentToState(infoHash)
      def get(infoHash: InfoHash): Option[State4Torrent] = torrentToState.get(infoHash)

      def update(torrent: InfoHash, state4Torrent: State4Torrent): Active =
        copy(torrentToState = torrentToState + (torrent -> state4Torrent))

      def addSubscriber(torrent: InfoHash, s: Tracker.Subscriber): Active =
        copy(torrentToState = torrentToState.get(torrent) match {
          case Some(state4Torrent @ State4Torrent(subscribers, _)) =>
            torrentToState + (torrent -> state4Torrent.copy(subscribers + s))
          case None => torrentToState + (torrent -> State4Torrent(Set(s), Map.empty))
        })

      def removeTorrent(torrent: InfoHash): Active = copy(torrentToState = torrentToState - torrent)
    }
  }

  private case class State4Torrent(
      subscribers: Set[Tracker.Subscriber],
      trackerToState: Map[InetSocketAddress, TrackerState]
  ) {
    def update(tracker: InetSocketAddress, newState: TrackerState): State4Torrent =
      State4Torrent(subscribers, trackerToState + (tracker -> newState))

    def setSubscriptions(subscriptions: Set[Tracker.Subscriber]): State4Torrent =
      State4Torrent(subscriptions, trackerToState)
  }

  sealed trait TrackerState

  object TrackerState {
    case class ConnectSent(txnId: Int, channel: Promise[(ConnectResponse, Long)]) extends TrackerState

    case class AnnounceSent(txnId: Int, connectionId: Long, channel: Promise[AnnounceResponse]) extends TrackerState

    case class AnnounceReceived(timestamp: Long, numPeers: Int) extends TrackerState
  }

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
  ): Tracker =
    new TrackerImpl(
      state = new AtomicReference(State.Ready),
      mainExecutor,
      scheduler,
      config,
      transactionIdGenerator
    )
}
