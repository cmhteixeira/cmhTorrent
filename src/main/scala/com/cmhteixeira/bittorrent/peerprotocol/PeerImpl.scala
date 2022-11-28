package com.cmhteixeira.bittorrent.peerprotocol

import org.apache.commons.codec.binary.Hex
import org.slf4j.{Logger, LoggerFactory}
import sun.nio.cs.{US_ASCII, UTF_8}

import java.io.InputStream
import java.net.{Inet4Address, InetSocketAddress, Socket, SocketAddress}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContextExecutorService
import scala.util.Try

private[bittorrent] final class PeerImpl private (
    socket: Socket,
    peerSocket: SocketAddress,
    config: Peer.Config,
    infoHash: Array[Byte],
    state: AtomicReference[Peer.State],
    executor: ExecutionContextExecutorService
) extends Peer {
  val logger: Logger = LoggerFactory.getLogger(getClass)
  logger.info(s"Initiating. Socket: $peerSocket")
  handShake()
  executor.execute(readThread())

  private def readThread(): Runnable =
    new Runnable {

      override def run(): Unit = {
        while (
          state.get() match {
            case _: Peer.Terminal => false
            case _ => true
          }
        ) {
          try {
            state.get() match {
              case Peer.Begin => receiveHandshake(socket.getInputStream)
              case state => ???
            }

            val msgType = socket.getInputStream.readNBytes(4)
            if (msgType == -1) state.set(Peer.OtherError("TCP connection severed."))
            else if (msgType == 0) ???
            else if (msgType == 1) ???
            else if (msgType == 2) ???
            else if (msgType == 3) ???
          } catch {
            case a => ???
          }
        }
      }
    }

  private def receiveNonHandshakeMessage(inputStream: InputStream): Unit = {
    val msgSizeBuffer = ByteBuffer.allocate(8)
    val msgTypeResponse = inputStream.readNBytes(4)
    if (msgTypeResponse.length != 4) state.set(Peer.OtherError("TCP connection severed."))
    else {
      val msgSize = msgSizeBuffer.putInt(0).put(msgTypeResponse).getLong
      val msgType = inputStream.read()
      if (msgSize == 0L) ??? // keep alive message
      else if (msgType == 0) choke(msgSize)
      else if (msgType == 1) unChoke(msgSize)
      else if (msgType == 2) interested(msgSize)
      else if (msgType == 3) notInterested(msgSize)
      else if (msgType == 4) ???
      else if (msgType == 5) ???
      else if (msgType == 6) ???
      else if (msgType == 7) ???
      else if (msgType == 8) ???
    }
  }

  private def choke(msgSize: Long): Unit = {
    if (msgSize != 1L) {
      logger.warn(s"Received choke but msg size is $msgSize")
    } else {
      logger.info("Received choke.")
    }
  }

  private def unChoke(msgSize: Long): Unit = ???
  private def interested(msgSize: Long): Unit = ???
  private def notInterested(msgSize: Long): Unit = ???

  private def receiveHandshake(input: InputStream): Unit = {
    (for {
      protocolLength <- toEither(input.read(), err => Peer.ErrorHandshake("Error extracting protocol length"))
      protocolLengthValid <-
        if (protocolLength == -1) Left(Peer.OtherError(s"Protocol length is -1.")) else Right(protocolLength)
      protocol <- toEither(input.readNBytes(protocolLengthValid), err => Peer.OtherError(err.getMessage))
      protocolValidated <-
        if (protocol.length != protocolLengthValid) Left(Peer.OtherError("Protocol length not correct"))
        else Right(protocol)
      reservedBytes <- toEither(ByteBuffer.wrap(input.readNBytes(8)).getLong, err => Peer.OtherError(err.getMessage))
      infoHashRes <- toEither(input.readNBytes(20), err => Peer.OtherError(err.getMessage))
      infoHashValid <-
        if (Hex.encodeHexString(infoHashRes) != Hex.encodeHexString(infoHash))
          Left(Peer.OtherError("InfoHash of request not matching response"))
        else Right(infoHashRes)
      peerId <- toEither(input.readNBytes(20), err => Peer.ErrorHandshake(err.getMessage))
      peerIdValid <-
        if (peerId.length != 20) Left(Peer.OtherError("PeerId of response not 20 bytes")) else Right(peerId)
    } yield Handshaked(
      reservedBytes,
      new String(peerIdValid, new UTF_8()),
      new String(protocolValidated, new UTF_8())
    )) match {
      case Left(value) =>
        if (!state.compareAndSet(Peer.Begin, value))
          logger.info(s"OMG....: ${value}")
      case Right(value) =>
        if (!state.compareAndSet(Peer.Begin, value))
          logger.info(s"OMG....: ${value}")
    }
  }

  private def toEither[A](f: => A, error: Throwable => Peer.State): Either[Peer.State, A] =
    Try(f).toEither.left.map(error)

  private def createHandShake: Either[Peer.State, Array[Byte]] = {
    Try {
      val handShake = ByteBuffer.allocate(68)
      handShake.put(19: Byte)
      handShake.put(PeerImpl.protocol.getBytes(new US_ASCII()))
      handShake.putLong(0)
      handShake.put(infoHash)
      handShake.put(config.myPeerId.getBytes)
      handShake.array()
    }.toEither.left.map(err => Peer.OtherError(err.getMessage))
  }

  private def handShake(): Unit = {
    (for {
      _ <- toEither(socket.connect(peerSocket, config.tcpConnectTimeoutMillis), err => Peer.TcpIssue(err.getMessage))
      input <- toEither(socket.getInputStream, error => Peer.TcpIssue(error.getMessage))
      output <- toEither(socket.getOutputStream, error => Peer.TcpIssue(error.getMessage))
      handShake <- createHandShake
      _ <- toEither(output.write(handShake), err => Peer.TcpIssue(err.getMessage))
      protocolLength <- toEither(input.read(), err => Peer.ErrorHandshake("Error extracting protocol length"))
      protocolLengthValid <-
        if (protocolLength == -1) Left(Peer.OtherError(s"Protocol length is -1.")) else Right(protocolLength)
      protocol <- toEither(input.readNBytes(protocolLengthValid), err => Peer.OtherError(err.getMessage))
      protocolValidated <-
        if (protocol.length != protocolLengthValid) Left(Peer.OtherError("Protocol length not correct"))
        else Right(protocol)
      reservedBytes <- toEither(ByteBuffer.wrap(input.readNBytes(8)).getLong, err => Peer.OtherError(err.getMessage))
      infoHashRes <- toEither(input.readNBytes(20), err => Peer.OtherError(err.getMessage))
      infoHashValid <-
        if (Hex.encodeHexString(infoHashRes) != Hex.encodeHexString(infoHash))
          Left(Peer.OtherError("InfoHash of request not matching response"))
        else Right(infoHashRes)
      peerId <- toEither(input.readNBytes(20), err => Peer.ErrorHandshake(err.getMessage))
      peerIdValid <-
        if (peerId.length != 20) Left(Peer.OtherError("PeerId of response not 20 bytes")) else Right(peerId)
    } yield Handshaked(
      reservedBytes,
      new String(peerIdValid, new UTF_8()),
      new String(protocolValidated, new UTF_8())
    )) match {
      case Left(value) =>
        if (!state.compareAndSet(Peer.Begin, value))
          logger.info(s"OMG....: ${value}")
      case Right(value) =>
        if (!state.compareAndSet(Peer.Begin, value))
          logger.info(s"OMG....: ${value}")
    }
  }

  override def getState: Peer.State = state.get()

}

object PeerImpl {
  private val protocol: String = "BitTorrent protocol"

  def apply(peerIp: Inet4Address, peerPort: Int, config: Peer.Config, infoHash: Array[Byte]): PeerImpl =
    new PeerImpl(
      new Socket(),
      new InetSocketAddress(peerIp, peerPort),
      config,
      infoHash,
      new AtomicReference[Peer.State](Peer.Begin),
      ???
    )
}
