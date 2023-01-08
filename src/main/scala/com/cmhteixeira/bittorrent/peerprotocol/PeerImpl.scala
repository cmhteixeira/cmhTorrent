package com.cmhteixeira.bittorrent.peerprotocol

import cats.implicits.toTraverseOps
import com.cmhteixeira.bittorrent.{InfoHash, PeerId}
import com.cmhteixeira.bittorrent.peerprotocol.PeerImpl.{Config, blockSize}
import com.cmhteixeira.bittorrent.peerprotocol.PeerMessages.Request
import com.cmhteixeira.bittorrent.peerprotocol.State.MyPieceState.{Asked, Want}
import com.cmhteixeira.bittorrent.peerprotocol.State.TerminalError.{SendingHandshake, TcpConnection}
import com.cmhteixeira.bittorrent.peerprotocol.State.{
  Begin,
  ConnectionState,
  Good,
  Handshaked,
  MyPieceState,
  PeerPieceState,
  PieceState,
  TerminalError
}
import org.slf4j.{Logger, LoggerFactory, MDC}

import java.net._
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

private[peerprotocol] final class PeerImpl private (
    socket: Socket,
    peerSocket: InetSocketAddress,
    config: Config,
    infoHash: InfoHash,
    state: AtomicReference[State],
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService,
    pieceLength: Int,
    numberOfPieces: Int
) extends Peer {
  private val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  def start(): Unit = {
    logger.info(s"Initiating '$peerSocket'.")
    scheduler.execute(connect)
  }

  private def sendKeepAlive: Runnable =
    new Runnable {

      def run(): Unit = {
        MDC.put("context", peerSocket.toString)
        Try(socket.getOutputStream.write(ByteBuffer.allocate(4).putInt(0).array())) match {
          case Failure(exception) => logger.warn(s"Failed to send keep alive message to '$peerSocket'.", exception)
          case Success(_) => logger.info(s"Keep alive message sent to '$peerSocket'.")
        }
      }
    }

  private def connect: Runnable =
    new Runnable {

      override def run(): Unit = {
        try {
          socket.connect(peerSocket, config.tcpConnectTimeoutMillis)
          state.set(State.begin.connected)
          scheduler.execute(sendHandShake)
          scheduler.scheduleAtFixedRate(sendKeepAlive, 100, 100, TimeUnit.SECONDS)
          mainExecutor.execute(
            ReadThread(socket, state, infoHash, peerSocket, numberOfPieces, config.downloadDir, pieceLength)
          )
        } catch {
          case error: SocketTimeoutException =>
            logger.warn(s"TCP connection timeout to '$peerSocket'.")
            setError(TcpConnection(error))
          case otherError: Throwable =>
            logger.warn(s"TCP connection error '$peerSocket'.", otherError)
            setError(TcpConnection(otherError))
        }
      }
    }

  private def sendHandShake: Runnable = {
    new Runnable {
      override def run(): Unit = {
        try {
          state.get() match {
            case Begin =>
              logger.warn(
                "THIS SHOULD BE IMPOSSIBLE.....Sending handshake, but not yet connected. Retrying later in 100 millis."
              )
              Thread.sleep(100)
              run()
            case State.TcpConnected =>
              socket.getOutputStream.write(PeerMessages.Handshake(infoHash, config.myPeerId).serialize)
              logger.info(s"Sent handshake to '$peerSocket'.")
            case state => logger.debug(s"Did not send handshake to '$peerSocket' as state is already '$state'.")
          }
        } catch {
          case e: Throwable =>
            logger.error(s"Error sending handshake to '$peerSocket'.")
            setError(SendingHandshake(e))
        }
      }
    }

  }

  override def getState: State = state.get()

  override def peerAddress: SocketAddress = peerSocket

  @tailrec
  override def download(pieceIndex: Int): Future[Path] = {
    logger.info(s"Download piece $pieceIndex.")
    val currentState = state.get()
    currentState match {
      case handshaked @ Handshaked(_, _, _, ConnectionState(amChocked, isInterested), _, pieces) =>
        pieces.get(pieceIndex) match {
          case Some(PieceState(MyPieceState.Have(path), _)) => Future.successful(path)
          case Some(PieceState(MyPieceState.Want(channel), _)) => channel.future
          case Some(PieceState(MyPieceState.Asked(channel), _)) => channel.future
          case Some(PieceState(MyPieceState.Downloading(channel, _), _)) => channel.future
          case Some(PieceState(MyPieceState.NeitherHaveOrWant, _)) =>
            if (handshaked.numberAsked == 0) {
              val channel = Promise[Path]()
              val newState = handshaked.updateMyState(pieceIndex, Asked(channel), ConnectionState(amChocked, true))
              if (!state.compareAndSet(currentState, newState)) download(pieceIndex)
              else
                (for {
                  _ <- if (isInterested) Try.apply(()) else sayIAmInterest
                  _ <- allRequestMessages(pieceIndex).traverse(requestPiece)
                } yield ()) match {
                  case Failure(exception) =>
                    val error = new Exception(s"Requesting piece $pieceIndex.", exception)
                    logger.warn("What is this madness", error)
                    channel.failure(error)
                    channel.future
                  case Success(_) =>
                    logger.info(s"Just asked piece $pieceIndex.")
                    channel.future
                }
            } else {
              val channel = Promise[Path]()
              val newState = handshaked.updateMyState(pieceIndex, Want(channel))
              if (!state.compareAndSet(currentState, newState)) download(pieceIndex)
              else {
                logger.info(s"Registered interested in piece $pieceIndex.")
                channel.future
              }
            }

          case None =>
            Future.failed(
              new IndexOutOfBoundsException(s"Number of pieces: ${pieces.size}. Index passed: $pieceIndex")
            )
        }
      case state => Future.failed(new IllegalStateException(s"Peer is in state '$state'. It cannot download piece."))
    }
  }

  private def sayIAmInterest: Try[Unit] = { //todo: improve this.
    logger.info("Informing I am interested.")
    val int = ByteBuffer.allocate(5).putInt(1).put(0x2: Byte).array()
    Try(socket.getOutputStream.write(int))
  }

  private def requestPiece(request: Request): Try[Unit] =
    Try(socket.getOutputStream.write(request.serialize))

  private def allRequestMessages(pieceIndex: Int): List[Request] =
    if (pieceIndex == numberOfPieces - 1) { // last piece
      logger.warn("Cannot do this yet ...")
      List()
    } else {
      if (pieceLength % blockSize == 0)
        (0 until (pieceLength / blockSize)).map(i => Request(pieceIndex, i * blockSize, blockSize)).toList
      else {
        val noNormalSizedBlocks = math.floor(pieceLength.toDouble / blockSize.toDouble).toInt
        val firstBlocks = (0 until noNormalSizedBlocks).map(i => Request(pieceIndex, i * blockSize, blockSize))
        val lastBlock = Request(pieceIndex, blockSize * noNormalSizedBlocks, pieceLength % blockSize)
        firstBlocks.toList :+ lastBlock
      }
    }

  override def hasPiece(index: Int): Boolean = {
    state.get() match {
      case Handshaked(_, _, _, _, _, pieces) =>
        pieces.get(index).getOrElse(false) match {
          case PieceState(_, PeerPieceState(has, _)) => has
        }
      case _ => false
    }
  }

  @tailrec
  private def setError(msg: TerminalError.Error): Unit = {
    val currentState = state.get()
    val newState = currentState match {
      case goodState: Good => TerminalError(goodState, msg)
      case error: TerminalError => error
    }

    if (!state.compareAndSet(currentState, newState)) setError(msg)
    else logger.info(s"Set error '$msg'.")
  }
}

object PeerImpl {

  private val blockSize = 16384 // todo: configure this
  case class Config(tcpConnectTimeoutMillis: Int, myPeerId: PeerId, downloadDir: Path)

  def apply(
      peerSocket: InetSocketAddress,
      config: Config,
      infoHash: InfoHash,
      mainExecutor: ExecutionContext,
      scheduledExecutorService: ScheduledExecutorService,
      numberOfPieces: Int,
      pieceLength: Int
  ): PeerImpl =
    new PeerImpl(
      new Socket(),
      peerSocket,
      config,
      infoHash,
      new AtomicReference[State](State.begin),
      mainExecutor,
      scheduledExecutorService,
      pieceLength,
      numberOfPieces
    )
}
