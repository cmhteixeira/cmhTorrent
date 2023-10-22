package com.cmhteixeira.bittorrent.peerprotocol

import com.cmhteixeira.bittorrent.peerprotocol.Peer.{BlockRequest, Subscriber}
import com.cmhteixeira.bittorrent.peerprotocol.PeerImpl.{Config, Message, State}
import com.cmhteixeira.bittorrent.{InfoHash, PeerId}
import com.cmhteixeira.bittorrent.peerprotocol.PeerImpl.State.{Handshaked, MyState, Unconnected}
import com.cmhteixeira.bittorrent.peerprotocol.PeerMessages.Request
import org.apache.commons.codec.binary.Hex
import org.slf4j.{Logger, LoggerFactory}
import scodec.bits.{BitVector, ByteVector}

import java.net._
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ScheduledExecutorService, ScheduledFuture, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

final class PeerImpl private (
    socket: Socket,
    peerSocket: InetSocketAddress,
    config: Config,
    infoHash: InfoHash,
    state: AtomicReference[State],
    peersThreadPool: ExecutionContext,
    scheduler: ScheduledExecutorService,
    numberOfPieces: Int
) extends Peer
    with PeerImpl.Handler {

  private val logger: Logger = LoggerFactory.getLogger("Peer." + s"${peerSocket.getHostString}:${peerSocket.getPort}")

  private def sendKeepAlive: Runnable =
    () =>
      state.get() match {
        case _: State.Handshaked =>
          Try(socket.getOutputStream.write(ByteBuffer.allocate(4).putInt(0).array())) match {
            case Failure(exception) => logger.warn(s"Failed to send keep alive message.", exception)
            case Success(_) => logger.info(s"Keep alive message sent.")
          }
        case state => logger.warn(s"Not sending scheduled handshake as state is '$state'.")
      }

  private def connect(): Runnable =
    () =>
      (for {
        _ <- Try(socket.connect(peerSocket, config.tcpConnectTimeoutMillis))
        _ = peersThreadPool.execute(ReadThread(socket, this, infoHash, peerSocket, numberOfPieces))
        _ <- Try(socket.getOutputStream.write(PeerMessages.Handshake(infoHash, config.myPeerId).serialize))
      } yield ()) match {
        case Failure(exception) =>
          val msg = "Connecting and sending handshake."
          logger.warn(msg, exception)
          internalShutdown(new RuntimeException(msg, exception))
        case Success(_) => ()
      }

  @tailrec
  override def download(blockRequest: BlockRequest): Future[ByteVector] = {
    val currentState = state.get()
    val BlockRequest(index, offSet, lengthBlock) = blockRequest
    currentState match {
      case handshaked @ State.Handshaked(
            _,
            _,
            _,
            State.MyState(State.ConnectionState(_, amInterested), requests),
            State.PeerState(_, _),
            _,
            _
          ) =>
        requests.get(blockRequest) match {
          case Some(State.BlockState.Sent(channel)) => channel.future
          case Some(State.BlockState.Received) =>
            Future.failed( // todo: better way to deal with this
              new IllegalArgumentException(s"Peer '$peerSocket' has already downloaded block '$blockRequest'.")
            )
          case None =>
            val channel = Promise[ByteVector]()
            val newState = handshaked.download(blockRequest, channel)
            if (!state.compareAndSet(currentState, newState)) download(blockRequest)
            else
              (for {
                _ <- if (amInterested) Try.apply(()) else sayIAmInterest
                _ <- requestPiece(Request(blockRequest.index, blockRequest.offSet, blockRequest.length))
              } yield ()) match {
                case Failure(exception) =>
                  val msg = s"Requesting block. Piece: $index, offset: $offSet, length: $lengthBlock."
                  val error = new Exception(msg, exception)
                  logger.warn(msg, exception)
                  channel.tryFailure(error)
                  channel.future
                case Success(_) =>
                  logger.info(s"Sent request for block. Piece: $index, offset: $offSet, length: $lengthBlock.")
                  channel.future
              }
        }
      case state => Future.failed(new IllegalStateException(s"Peer cannot download. Peer state is: '$state'."))
    }
  }

  private def sayIAmInterest: Try[Unit] = { // todo: improve this.
    logger.info("Informing I am interested.")
    val int = ByteBuffer.allocate(5).putInt(1).put(0x2: Byte).array()
    Try(socket.getOutputStream.write(int))
  }

  private def requestPiece(request: Request): Try[Unit] =
    Try(socket.getOutputStream.write(request.serialize))

  @tailrec
  override def subscribe(
      s: Peer.Subscriber
  ): Future[Unit] = {
    val currentState = state.get()
    val promise = Promise[Unit]()
    currentState match {
      case State.Unsubscribed =>
        if (!state.compareAndSet(currentState, Unconnected(s, promise))) subscribe(s)
        else {
          peersThreadPool.execute(connect())
          promise.future
        }
      case State.Unconnected(_, _) => Future.failed(new IllegalStateException("This peer has already been subscribed."))
      case _: State.Handshaked => Future.failed(new IllegalStateException("This peer has already been subscribed."))
      case State.Closed => Future.failed(new IllegalStateException("This peer has already closed."))
    }
  }

  override def close(): Unit = internalShutdown(new IllegalStateException("Peer was closed from outside."))

  override def address: InetSocketAddress = peerSocket

  private def internalShutdown(exception: Exception): Unit = {
    val currentState = state.get()
    logger.warn(s"Shutting down. Closing state: $currentState", exception)
    state.set(PeerImpl.State.Closed)
    Try { socket.close() } match {
      case Failure(exception) => logger.warn("Closing the socket.", exception)
      case Success(_) => ()
    }

    currentState match {
      case State.Closed => ()
      case State.Unsubscribed => ()
      case Unconnected(_, channel) => channel.tryFailure(exception)
      case i: State.Handshaked =>
        logger.info(s"Shutdown. Unregistering keep alive? ${i.keepAliveTasks.nonEmpty}.")
        i.keepAliveTasks.foreach(_.cancel(true))
        logger.info(s"Shutdown. Signalling subscriber.")
        i.subscriber.onError(exception)
    }
  }

  private def receivedBlock(pieceIndex: Int, offSet: Int, block: ByteVector): Unit = {
    val currentState = state.get()
    currentState match {
      case handshaked @ Handshaked(_, _, _, MyState(_, requests), _, _, _) =>
        val blockRequest = BlockRequest(pieceIndex, offSet, block.size.toInt)
        requests.get(blockRequest) match {
          case Some(PeerImpl.State.BlockState.Sent(channel)) =>
            val newState = handshaked.received(blockRequest)
            if (!state.compareAndSet(currentState, newState)) receivedBlock(pieceIndex, offSet, block)
            else channel.trySuccess(block) // todo: Check if usage of try-complete is appropriate
          case Some(PeerImpl.State.BlockState.Received) => logger.warn("Weird....")
          case None =>
            logger.warn(s"Received unregistered block for piece $pieceIndex. Offset: $offSet, length: ${block.length}")
        }
      case state => logger.warn(s"Received block for piece $pieceIndex, but state is '$state'.")
    }
  }

  override def getState: State = state.get()

  override def message(msg: PeerImpl.Message): Unit = msg match {
    case Message.Error(exception) => internalShutdown(exception)
    case Message.ReceivedHandshake(handshaked) => receiveHandshake(handshaked)
    case Message.Choke => peerChoked()
    case Message.UnChoke => peerUnChoked()
    case Message.Interested => updateHandShakedState(_.peerInterested)
    case Message.Uninterested => updateHandShakedState(_.peerNotInterested)
    case Message.HasPiece(idx) => peerHasPiece(idx)
    case Message.HasPieces(idx) => idx.zipWithIndex.foreach { case (hasPiece, i) => if (hasPiece) peerHasPiece(i) }
    case i @ Message.Request(_, _, _) => logger.info(s"Received '$i'. Ignoring for now.")
    case Message.Piece(idx, offset, data) => receivedBlock(idx, offset, data)
    case i @ Message.Cancel(_, _, _) => logger.info(s"Received '$i'. Ignoring for now.")
    case Message.MessageTypeUnknown(msgType) =>
      internalShutdown(new RuntimeException(s"Received message of unknown type: $msgType"))
    case Message.KeepAlive => logger.info("Received keep-alive.")
  }

  @tailrec
  private def receiveHandshake(handshaked: State.Handshaked): Unit = {
    val currentState = state.get()
    currentState match {
      case Unconnected(_, channel) =>
        if (!state.compareAndSet(currentState, handshaked)) receiveHandshake(handshaked)
        else {
          Try(scheduler.scheduleAtFixedRate(sendKeepAlive, 100, 100, TimeUnit.SECONDS)) match {
            case Failure(exception) =>
              val msg = s"Scheduling keep alive messages failed. Have you shutdown the scheduler?"
              internalShutdown(new RuntimeException(msg, exception))
            case Success(keepAliveTask) =>
              registerKeepAliveTask(keepAliveTask.asInstanceOf[ScheduledFuture[Unit]]) match {
                case Failure(exception) =>
                  keepAliveTask.cancel(true)
                  internalShutdown(exception.asInstanceOf[Exception])
                case Success(_) => channel.trySuccess(())
              }
          }
        }
      case i =>
        internalShutdown(
          new RuntimeException(s"Impossible state transition. Received '$handshaked', when state is $i")
        )
    }
  }

  private def registerKeepAliveTask(keepAliveTask: ScheduledFuture[Unit]): Try[Unit] = {
    val currentState = state.get()
    currentState match {
      case handshaked: State.Handshaked =>
        if (!state.compareAndSet(currentState, handshaked.registerKeepAliveTaskHandler(keepAliveTask)))
          registerKeepAliveTask(keepAliveTask)
        else Success(())
      case state => Failure(new RuntimeException(s"Tried to register keep-alive, but state is '$state'"))
    }
  }

  private def updateHandShakedState(f: Handshaked => Handshaked): Unit = {
    val currentState = state.get()
    currentState match {
      case hand: State.Handshaked => if (!state.compareAndSet(currentState, hand)) updateHandShakedState(f)
      case otherState => logger.warn(s"Updating handshaked state, but current state is $otherState.")
    }
  }
  private def peerUnChoked(): Unit = {
    updateHandShakedState(_.unShokePeer)
    state.get() match {
      case handshaked: State.Handshaked => handshaked.subscriber.unChocked()
      case otherState => logger.warn(s"Unchoking, but state is '$otherState'.")
    }
  }

  private def peerChoked(): Unit = {
    updateHandShakedState(_.chokePeer)
    state.get() match {
      case handshaked: State.Handshaked => handshaked.subscriber.chocked()
      case otherState => logger.warn(s"Choking, but state is '$otherState'.")
    }
  }

  private def peerHasPiece(idx: Int): Unit = {
    updateHandShakedState(_.pierHasPiece(idx))
    state.get() match {
      case handshaked: State.Handshaked => handshaked.subscriber.hasPiece(idx)
      case otherState => logger.warn("")
    }
  }
  override def piece(idx: Int): Unit = {
    val currentState = state.get()
    currentState match {
      case handshaked @ State.Handshaked(
            _,
            _,
            _,
            State.MyState(State.ConnectionState(amChocked, _), _),
            State.PeerState(State.ConnectionState(_, _), _),
            _,
            _
          ) =>
        if (amChocked) {
          if (!state.compareAndSet(currentState, handshaked.unShokeMe)) piece(idx)
          else unChokeMe()
        }
        informPiece(idx)
      case _ => ()
    }
  }

  private def unChokeMe(): Unit = { // todo: improve this.
    logger.info("Informing I am unchoked (So I can send.).")
    Try(socket.getOutputStream.write(PeerMessages.Unchoke.serialize.toArray)) match {
      case Failure(exception) => internalShutdown(new Exception("Sending Unchoke.", exception))
      case Success(_) => logger.info("Informed peer I am unchoked.")
    }
  }

  private def informPiece(piece: Int): Unit = {
    logger.info(s"Informing I have piece $piece")
    val have = PeerMessages.Have(piece)
    Try(socket.getOutputStream.write(have.serialize.toArray)) match {
      case Failure(exception) => internalShutdown(new Exception(s"Sending  $have.", exception))
      case Success(_) => logger.info(s"Informed peer I have piece $piece")
    }
  }

}

object PeerImpl {

  private[peerprotocol] trait Handler {
    def getState: State
    def message(msg: Message): Unit
  }

  sealed trait Message

  object Message {
    def error(msg: String): Error = Error(new Exception(msg))
    case class Error(exception: Exception) extends Message
    case class ReceivedHandshake(handshaked: Handshaked) extends Message
    case object Choke extends Message
    case object UnChoke extends Message
    case object Interested extends Message
    case object Uninterested extends Message
    case class HasPiece(idx: Int) extends Message
    case class HasPieces(idx: List[Boolean]) extends Message
    case class Request(idx: Int, offset: Int, len: Int) extends Message
    case class Piece(idx: Int, offset: Int, data: ByteVector) extends Message
    case class Cancel(idx: Int, offset: Int, len: Int) extends Message
    case class MessageTypeUnknown(msgType: Int) extends Message

    case object KeepAlive extends Message
  }

  case class Config(tcpConnectTimeoutMillis: Int, myPeerId: PeerId)

  sealed trait State

  object State {

    case object Unsubscribed extends State

    case class Unconnected(subscriber: Peer.Subscriber, channel: Promise[Unit]) extends State {
      override def toString: String = s"Unconnected[handshakeReceived: ${channel.isCompleted}]"
    }

    case object Closed extends State

    case class Handshaked(
        reservedBytes: Long,
        peerId: String,
        protocol: String,
        me: MyState,
        peer: PeerState,
        keepAliveTasks: Option[ScheduledFuture[Unit]],
        subscriber: Subscriber
    ) extends State {
      def chokeMe: Handshaked = copy(me = me.choke)
      def chokePeer: Handshaked = copy(peer = peer.choke)
      def unShokeMe: Handshaked = copy(me = me.unChoke)
      def unShokePeer: Handshaked = copy(peer = peer.unChoke)
      def meInterested: Handshaked = copy(me = me.interested)
      def peerInterested: Handshaked = copy(peer = peer.interested)
      def meNotIntested: Handshaked = copy(me = me.notInterested)
      def peerNotInterested: Handshaked = copy(peer = peer.notInterested)

      def peerPieces(bitField: List[Boolean]): Handshaked = copy(peer = peer.hasPieces(bitField))

      def pierHasPiece(index: Int): Handshaked = copy(peer = peer.hasPiece(index))

      def download(block: BlockRequest, channel: Promise[ByteVector]): Handshaked =
        copy(me =
          MyState(
            connectionState = me.connectionState.copy(isInterested = true),
            requests = me.requests + (block -> State.BlockState.Sent(channel))
          )
        )

      def received(blockRequest: BlockRequest): Handshaked =
        copy(me = me.copy(requests = me.requests + (blockRequest -> State.BlockState.Received)))

      def registerKeepAliveTaskHandler(task: ScheduledFuture[Unit]): Handshaked =
        copy(keepAliveTasks = Some(task))
      override def toString: String = {
        val decodedPeerId = Try(Hex.decodeHex(peerId)).getOrElse("unknown-id")
        s"Handshaked[$reservedBytes,$decodedPeerId,$protocol,keepAlive=${keepAliveTasks.nonEmpty},$me,$peer]"
      }
    }

    case class MyState(connectionState: ConnectionState, requests: Map[BlockRequest, BlockState]) {
      def choke: MyState = copy(connectionState = connectionState.choke)
      def unChoke: MyState = copy(connectionState = connectionState.unChoke)
      def interested: MyState = copy(connectionState = connectionState.interested)
      def notInterested: MyState = copy(connectionState = connectionState.notInterested)

      override def toString: String = s"MyState[$connectionState, requests=${requests.size}]"
    }

    case class PeerState(connectionState: ConnectionState, piecesBitField: BitVector) {
      def choke: PeerState = copy(connectionState = connectionState.choke)
      def unChoke: PeerState = copy(connectionState = connectionState.unChoke)
      def interested: PeerState = copy(connectionState = connectionState.interested)
      def notInterested: PeerState = copy(connectionState = connectionState.notInterested)
      def hasPiece(index: Int): PeerState = copy(piecesBitField = piecesBitField.set(index))
      def hasPieces(indexes: List[Boolean]): PeerState = copy(piecesBitField = BitVector.bits(indexes))
      override def toString: String = s"PeerState[$connectionState, #Pieces=Unknown]"
    }

    sealed trait BlockState

    object BlockState {
      case class Sent(channel: Promise[ByteVector]) extends BlockState
      case object Received extends BlockState
    }

    case class ConnectionState(isChocked: Boolean, isInterested: Boolean) {
      def choke = copy(isChocked = true)
      def unChoke = copy(isChocked = false)
      def interested = copy(isInterested = true)
      def notInterested = copy(isInterested = false)
      override def toString: String = s"State[chocked=$isChocked, interested=$isInterested]"
    }

  }

  def apply(
      peerSocket: InetSocketAddress,
      config: Config,
      infoHash: InfoHash,
      peersThreadPool: ExecutionContext,
      scheduledExecutorService: ScheduledExecutorService,
      numberOfPieces: Int
  ): PeerImpl =
    new PeerImpl(
      new Socket(),
      peerSocket,
      config,
      infoHash,
      new AtomicReference[State](State.Unsubscribed),
      peersThreadPool,
      scheduledExecutorService,
      numberOfPieces
    )
}
