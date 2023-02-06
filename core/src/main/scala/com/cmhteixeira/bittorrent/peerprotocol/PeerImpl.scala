package com.cmhteixeira.bittorrent.peerprotocol

import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import com.cmhteixeira.bittorrent.{InfoHash, PeerId}
import com.cmhteixeira.bittorrent.peerprotocol.PeerImpl.Config
import com.cmhteixeira.bittorrent.peerprotocol.PeerMessages.Request
import com.cmhteixeira.bittorrent.peerprotocol.State.BlockState.{Received, Sent}
import com.cmhteixeira.bittorrent.peerprotocol.State.TerminalError.{
  IDisconnected,
  ImpossibleState,
  ImpossibleToScheduleKeepAlives,
  SendingHaveOrAmInterestedMessage,
  TcpConnection
}
import com.cmhteixeira.bittorrent.peerprotocol.State.{
  ConnectionState,
  Good,
  Handshaked,
  MyState,
  PeerState,
  TerminalError
}
import org.slf4j.{Logger, LoggerFactory}
import scodec.bits.{BitVector, ByteVector}

import java.net._
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ScheduledExecutorService, ScheduledFuture, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

private[peerprotocol] final class PeerImpl private (
    socket: Socket,
    peerSocket: InetSocketAddress,
    config: Config,
    infoHash: InfoHash,
    state: AtomicReference[State],
    peersThreadPool: ExecutionContext,
    scheduler: ScheduledExecutorService,
    numberOfPieces: Int
) extends Peer {

  private val logger: Logger = LoggerFactory.getLogger("Peer." + s"${peerSocket.getHostString}:${peerSocket.getPort}")

  def start(): Unit = {
    logger.info(s"Initiating ...")
    scheduler.execute(connect)
  }

  private def sendKeepAlive: Runnable =
    new Runnable {

      def run(): Unit =
        state.get() match {
          case _: Handshaked =>
            Try(socket.getOutputStream.write(ByteBuffer.allocate(4).putInt(0).array())) match {
              case Failure(exception) => logger.warn(s"Failed to send keep alive message.", exception)
              case Success(_) => logger.info(s"Keep alive message sent.")
            }
          case state => logger.warn(s"Not sending scheduled handshake as state is '$state'.")
        }
    }

  private def registerKeepAliveTask(keepAliveTask: ScheduledFuture[Unit]): Unit = {
    val currentState = state.get()
    currentState match {
      case handshaked: Handshaked =>
        if (!state.compareAndSet(currentState, handshaked.registerKeepAliveTaskHandler(keepAliveTask)))
          registerKeepAliveTask(keepAliveTask)
      case TerminalError(_, error) =>
        keepAliveTask.cancel(true)
        logger.warn(s"Not scheduling keep-alive tasks as peer in Terminal error state: '$error'.")
      case state =>
        logger.warn(s"This state should be impossible at the stage of scheduling keep-alive tasks: '$state'.")
        keepAliveTask.cancel(true)
        setError(ImpossibleState(state, "This state should be impossible at the stage of scheduling keep-alive tasks"))
        socket.close()
    }
  }

  private def onceHandshakeReceived(handshakeChannel: Future[Unit]): Unit =
    handshakeChannel.onComplete {
      case Failure(exception) => logger.error("Failed to receive handshake. Doing nothing.", exception)
      case Success(_) =>
        Try(scheduler.scheduleAtFixedRate(sendKeepAlive, 100, 100, TimeUnit.SECONDS)) match {
          case Failure(exception) =>
            logger.warn("Scheduling keep alive messages to peer failed. Have you shutdown the scheduler?", exception)
            setError(ImpossibleToScheduleKeepAlives(exception))
            socket.close()
          case Success(keepAliveTask) => registerKeepAliveTask(keepAliveTask.asInstanceOf[ScheduledFuture[Unit]])
        }
    }(peersThreadPool)

  private def connect: Runnable =
    new Runnable {

      def run(): Unit =
        (for {
          _ <- Try(socket.connect(peerSocket, config.tcpConnectTimeoutMillis))
          promise = Promise[Unit]() // ReadThread completes this once it receives the handshake.
          _ = state.set(State.begin.connected(promise))
          _ = peersThreadPool.execute(ReadThread(socket, state, infoHash, peerSocket, numberOfPieces))
          _ <- Try(socket.getOutputStream.write(PeerMessages.Handshake(infoHash, config.myPeerId).serialize))
        } yield promise) match {
          case Failure(exception) =>
            logger.warn("Connecting or sending handshake.", exception)
            setError(TcpConnection(exception))
          case Success(promise) => onceHandshakeReceived(promise.future)
        }
    }

  override def getState: Peer.PeerState =
    state.get() match {
      case State.TcpConnected(_) => Peer.TcpConnected
      case State.Begin => Peer.Begin
      case Handshaked(_, peerId, _, _, peer, _) =>
        Peer.HandShaked(
          peerId = peerId,
          choked = peer.connectionState.isChocked,
          interested = peer.connectionState.isInterested,
          pieces = peer.piecesBitField
        )
      case TerminalError(_, _) => Peer.Error("Internal error")
    }

  override def peerAddress: SocketAddress = peerSocket

  @tailrec
  override def download(blockRequest: BlockRequest): Future[ByteVector] = {
    val currentState = state.get()
    val BlockRequest(index, offSet, lengthBlock) = blockRequest
    currentState match {
      case handshaked @ Handshaked(_, _, _, MyState(ConnectionState(_, amInterested), requests), PeerState(_, _), _) =>
        requests.get(blockRequest) match {
          case Some(Sent(channel)) => channel.future
          case Some(Received) =>
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
                  logger.warn(msg, error)
                  channel.tryFailure(error)
                  setError(SendingHaveOrAmInterestedMessage(exception))
                  channel.future
                case Success(_) =>
                  logger.info(s"Sent request for block. Piece: $index, offset: $offSet, length: $lengthBlock.")
                  channel.future
              }
        }
      case state => Future.failed(new IllegalStateException(s"Peer cannot download. Peer state is: '$state'."))
    }
  }

  private def sayIAmInterest: Try[Unit] = { //todo: improve this.
    logger.info("Informing I am interested.")
    val int = ByteBuffer.allocate(5).putInt(1).put(0x2: Byte).array()
    Try(socket.getOutputStream.write(int))
  }

  private def requestPiece(request: Request): Try[Unit] =
    Try(socket.getOutputStream.write(request.serialize))

  override def hasPiece(index: Int): Boolean =
    state.get() match {
      case Handshaked(_, _, _, _, PeerState(_, piecesBitField), _) => piecesBitField.get(index)
      case _ => false
    }

  override def pieces: BitVector =
    state.get() match {
      case Handshaked(_, _, _, _, PeerState(_, piecesBitField), _) => piecesBitField
      case _ => BitVector.low(numberOfPieces)
    }

  @tailrec
  private def setError(msg: TerminalError.Error): Unit = {
    val currentState = state.get()
    currentState match {
      case handshaked: Handshaked =>
        val error = TerminalError(handshaked, msg)
        if (!state.compareAndSet(currentState, error)) setError(msg)
        else {
          logger.info(s"Error '$msg' encountered. Closing connection")
          handshaked.keepAliveTasks match {
            case Some(value) =>
              logger.info("Cancelling keep-alive tasks.")
              value.cancel(false)
            case None => logger.info("No keep alive tasks to cancel.")
          }
          logger.info("???")
          handshaked.me.requests.collect {
            case (_, Sent(promiseCompletion)) => //todo: Check if usage of try-complete is appropriate
              promiseCompletion.tryFailure(new Exception(s"Impossible to complete: $msg."))
          }
          socket.close()
        }
      case goodState: Good =>
        val error = TerminalError(goodState, msg)
        if (!state.compareAndSet(currentState, error)) setError(msg)
        else {
          logger.info(s"Error '$msg' encountered. Closing connection")
          socket.close()
        }
      case _: TerminalError => ()
    }
  }

  override def disconnect(): Unit = {
    logger.info("Disconnecting.")
    setError(IDisconnected)
  }

}

object PeerImpl {

  case class Config(tcpConnectTimeoutMillis: Int, myPeerId: PeerId)

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
      new AtomicReference[State](State.begin),
      peersThreadPool,
      scheduledExecutorService,
      numberOfPieces
    )
}
