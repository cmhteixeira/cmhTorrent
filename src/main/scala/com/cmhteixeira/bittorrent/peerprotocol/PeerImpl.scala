package com.cmhteixeira.bittorrent.peerprotocol

import com.cmhteixeira.bittorrent.InfoHash
import com.cmhteixeira.bittorrent.peerprotocol.State.{Begin, HandshakeError, TcpConnected}
import org.slf4j.{Logger, LoggerFactory, MDC}
import sun.nio.cs.US_ASCII

import java.net._
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

private[peerprotocol] final class PeerImpl private (
    socket: Socket,
    peerSocket: SocketAddress,
    config: Peer.Config,
    infoHash: InfoHash,
    state: AtomicReference[State],
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService,
    pieces: List[String]
) extends Peer {
  private val logger: Logger = LoggerFactory.getLogger(getClass)
  MDC.put("peer-socket", peerSocket.toString)
  logger.info(s"Initiating. Socket: $peerSocket")
  scheduler.execute(connect)
  mainExecutor.execute(ReadThread(socket, state, infoHash, peerSocket, pieces))
  scheduler.schedule(sendHandShake, 2, TimeUnit.SECONDS)

  private def sendKeepAlive(): Unit =
    Try(socket.getOutputStream.write(ByteBuffer.allocate(4).putInt(0).array())) match {
      case Failure(exception) =>
        val msg = "Failed to send keep alive"
        logger.error(msg, exception)
      case Success(_) =>
        logger.info("Sent keep alive.")
    }

  private def connect: Runnable =
    new Runnable {

      override def run(): Unit = {
        MDC.put("peer-socket", peerSocket.toString)
        try {
          socket.connect(peerSocket, config.tcpConnectTimeoutMillis)
          state.set(State.begin.connected)
        } catch {
          case _: SocketTimeoutException =>
            val msg = s"TCP connection timeout."
            logger.warn(msg)
            state.set(State.begin.connectedError(msg))
          case _ =>
            val msg = s"TCP connection error. Not connection timeout."
            logger.warn(msg)
            state.set(State.begin.connectedError(msg))
        }
      }
    }

  private def sendHandShake: Runnable = {
    new Runnable {
      override def run(): Unit = {
        MDC.put("peer-socket", peerSocket.toString)
        try {
          state.get() match {
            case _: Begin =>
              logger.info("Sending handshake, but not yet connected. Retrying later.")
              Thread.sleep(100)
              run()
            case connected: State.TcpConnected =>
              createHandShake(connected) match {
                case Left(value) =>
                  logger.info("Error creating handshake.")
                  state.set(connected.handshakeError("Error creating handhsake."))

                case Right(handshake) =>
                  logger.info(s"Sending handshake to ${peerSocket}")
                  socket.getOutputStream.write(handshake)
              }
            case state => logger.info(s"Doing nothing. State is already $state")
          }
        } catch {
          case e =>
            val msg = s"Error sending handshake."
            logger.error(msg)
            state.set(State.begin.connectedError(msg))
        }
      }
    }

  }

  private def toEither[A, B](f: => A, error: Throwable => B): Either[B, A] =
    Try(f).toEither.left.map(error)

  private def createHandShake(connected: TcpConnected): Either[HandshakeError, Array[Byte]] =
    Try {
      val handShake = ByteBuffer.allocate(68)
      handShake.put(19: Byte)
      handShake.put(PeerImpl.protocol.getBytes(new US_ASCII()))
      handShake.putLong(0)
      handShake.put(infoHash.bytes)
      handShake.put(config.myPeerId.getBytes)
      handShake.array()
    }.toEither.left.map(err => connected.handshakeError(err.getMessage))

  override def getState: State = state.get()

  override def peerAddress: SocketAddress = peerSocket

  override def download(pieceHash: String): Future[Path] =
    Future.failed(
      new NotImplementedError(s"Download piece with hash '$pieceHash'.This API method is yet to be implemented.")
    )
}

object PeerImpl {
  private val protocol: String = "BitTorrent protocol"

  def apply(
      peerSocket: InetSocketAddress,
      config: Peer.Config,
      infoHash: InfoHash,
      mainExecutor: ExecutionContext,
      scheduledExecutorService: ScheduledExecutorService,
      pieces: List[String]
  ): PeerImpl =
    new PeerImpl(
      new Socket(),
      peerSocket,
      config,
      infoHash,
      new AtomicReference[State](State.begin),
      mainExecutor,
      scheduledExecutorService,
      pieces
    )
}
