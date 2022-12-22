package com.cmhteixeira.bittorrent.peerprotocol

import com.cmhteixeira.bittorrent.InfoHash
import com.cmhteixeira.bittorrent.peerprotocol.State._
import org.apache.commons.codec.binary.Hex
import org.slf4j.{Logger, LoggerFactory, MDC}
import sun.nio.cs.UTF_8

import java.io.InputStream
import java.net.{Socket, SocketAddress}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

private[peerprotocol] class ReadThread private (
    socket: Socket,
    state: AtomicReference[State],
    infoHash: InfoHash,
    peerAddress: SocketAddress,
    pieces: List[String]
) extends Runnable {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  @tailrec
  override final def run(): Unit = {
    MDC.put("peer-socket", peerAddress.toString)
    state.get() match {
      case begin @ Begin =>
        logger.info("Not yet connected.")
        Thread.sleep(100)
        run()
      case tcpConnected @ TcpConnected =>
        receiveHandshake(tcpConnected, socket.getInputStream)
        run()
      case handshaked: Handshaked =>
        Try(receiveNonHandshakeMessage(handshaked, socket.getInputStream)) match {
          case Failure(exception) =>
            logger.warn("Error receiving non-handshake message. Exiting and setting state to an error.", exception)
            setError(s"Error receiving non-handshake message: ${exception.toString}")
          case Success(_) => run()
        }
      case _: HandshakeError =>
        logger.warn(s"Handshake error. Exiting.")
      case _: TerminalError =>
        logger.warn(s"Terminal error. Exiting.")
    }
  }

  @tailrec
  private def setError(msg: String): Unit = {
    val currentState = state.get()
    val newState = currentState match {
      case begin @ Begin => begin.connectedError(msg)
      case error: HandshakeError => error
      case error: TerminalError => error
      case handshaked: Handshaked => handshaked.error(msg)
    }

    if (!state.compareAndSet(currentState, newState)) setError(msg)
    else logger.info(s"Changed state from '$currentState' to '$newState'")
  }

  private def receiveNonHandshakeMessage(currentState: Handshaked, inputStream: InputStream): Unit = {
    val msgTypeResponse = inputStream.readNBytes(4)
    if (msgTypeResponse.length != 4) state.set(currentState.error("Connection severed"))
    else {
      val msgSize = ByteBuffer.allocate(8).putInt(0).put(msgTypeResponse).clear().getLong
      if (msgSize == 0L) logger.info(s"Keep alive message from '${socket.getRemoteSocketAddress}'.")
      else {
        val msgType = inputStream.read()
        if (msgType == 0) chokeAndInterested(msgSize, "Choke", _.chokePeer)
        else if (msgType == 1) chokeAndInterested(msgSize, "Unchoke", _.unShokePeer)
        else if (msgType == 2) chokeAndInterested(msgSize, "Interested", _.peerInterested)
        else if (msgType == 3) chokeAndInterested(msgSize, "Not interested", _.peerNotInterested)
        else if (msgType == 4) have(msgSize)
        else if (msgType == 5) receiveBitfield(msgSize)
        else if (msgType == 6) request(msgSize)
        else if (msgType == 7) piece(msgSize)
        else if (msgType == 8) cancel(msgSize)
        else msgTypeNotRecognized(msgType)
      }
    }
  }

  private def cancel(msgSize: Long): Unit = {
    logger.info("Received 'cancel' message")
    if (msgSize != 13) {
      val msg = s"'request' message has an unexpected size. Expected: 13 bytes. Actual $msgSize"
      logger.warn(msg)
      setError(msg)
    } else {
      val payload = socket.getInputStream.readNBytes(12)
      if (payload.length != 12) setError("Did not read 12 bytes")
      else {
        val pieceIndex = ByteBuffer.allocate(8).putInt(0).put(payload, 0, 4).clear().getLong
        val byteOffset = ByteBuffer.allocate(8).putInt(0).put(payload, 4, 4).clear().getLong
        val length = ByteBuffer.allocate(8).putInt(0).put(payload, 8, 4).clear().getLong
        logger.info(s"Peer has cancelled request of $length bytes starting at $byteOffset from piece $pieceIndex.")
      }
    }
  }

  private def piece(msgSize: Long): Unit = {
    val blockSize = (msgSize - 9).toInt // todo: Fix cast
    logger.info(s"Received 'piece' block. Block size: $blockSize bytes.")
    val payload = socket.getInputStream.readNBytes((msgSize - 1).toInt) // todo: Fix cast.
    if (payload.length != (msgSize - 1)) setError(s"Did not read ${msgSize - 1} bytes")
    else {
      val pieceIndex = ByteBuffer.allocate(8).putInt(0).put(payload, 0, 4).clear().getLong
      val byteOffset = ByteBuffer.allocate(8).putInt(0).put(payload, 4, 4).clear().getLong
      val pieceBlock = ByteBuffer.allocate(blockSize).put(payload, 8, blockSize).clear().array()
      logger.info(s"Peer has sent $blockSize bytes starting at $byteOffset from piece $pieceIndex.")
    }
  }

  private def request(msgSize: Long): Unit = {
    logger.info("Received 'request' message")
    if (msgSize != 13) {
      val msg = s"'request' message has an unexpected size. Expected: 13 bytes. Actual $msgSize"
      logger.warn(msg)
      setError(msg)
    } else {
      val payload = socket.getInputStream.readNBytes(12)
      if (payload.length != 12) setError("Did not read 12 bytes")
      else {
        val pieceIndex = ByteBuffer.allocate(8).putInt(0).put(payload, 0, 4).clear().getLong
        val byteOffset = ByteBuffer.allocate(8).putInt(0).put(payload, 4, 4).clear().getLong
        val length = ByteBuffer.allocate(8).putInt(0).put(payload, 8, 4).clear().getLong
        logger.info(s"Peer has request $length bytes starting at $byteOffset from piece $pieceIndex.")
      }
    }
  }

  private def have(msgSize: Long): Unit = {
    logger.info(s"Received 'have' message")
    if (msgSize != 5) {
      val msg = s"'have' message has an unexpected size. Expected: 4 bytes. Actual $msgSize"
      logger.warn(msg)
      setError(msg)
    } else {
      val payload = socket.getInputStream.readNBytes(4)
      if (payload.length != 4) setError("Did not read 4 bytes")
      else {
        val pieceIndex = ByteBuffer.allocate(8).putInt(0).put(payload).clear().getLong
        logger.info(s"Peer has piece $pieceIndex")
        addPeerPiece(pieceIndex.toInt) //todo: Fix cast.
      }
    }
  }

  private def addPeerPiece(index: Int): Unit = {
    state.get() match {
      case handshaked: Handshaked =>
        val newState = handshaked.addPeerPiece(index)
        if (!state.compareAndSet(handshaked, newState)) addPeerPiece(index)
        else ()
      case _ => () //todo fix.
    }
  }

  private def receiveBitfield(msgSize: Long): Unit = {
    logger.info(s"Received bitfield message")
    val expectedNumberBytes = math.ceil(pieces.size.toDouble / 8d)
    val bitFieldSize = msgSize - 1
    if (bitFieldSize != expectedNumberBytes) {
      val msg = s"Expected number of bytes in 'bitfield' message: $expectedNumberBytes. Actual: $bitFieldSize"
      logger.warn(msg)
      setError(msg)
    }

    val bytesToRead: Int = (msgSize - 1).toInt // todo: fix this cast.
    val bitField = ByteBuffer.allocate(bytesToRead).array()
    socket.getInputStream.readNBytes(bitField, 0, bitField.length)

    logger.info(
      s"bitFieldArray is: ${bitField.map(theByte => String.format("%8s", Integer.toBinaryString(theByte & 0xff)).replace(' ', '0')).mkString("")}"
    )
    val peerPieces = pieces.zipWithIndex.map {
      case (pieceHash, i) =>
        val byteIndex = math.floor(i * 1d / 8d).toInt
        val theByte = bitField(byteIndex)
        val bitIndexWithinByte = i % 8
        val isSet = (theByte & (1.toByte << (7 - bitIndexWithinByte))) != 0
        (pieceHash, isSet)
    }
    setPeerPieces(peerPieces)
  }

  @tailrec
  private def setPeerPieces(in: List[(String, Boolean)]): Unit =
    state.get() match {
      case handshaked: Handshaked =>
        val newState = handshaked.peerPieces(in)
        if (!state.compareAndSet(handshaked, newState)) setPeerPieces(in)
        else ()
      case _ => () // todo: Fix.
    }

  private def msgTypeNotRecognized(msgType: Int): Unit = {
    logger.info(s"Received message of type $msgType, which is not recognized.")
    state.get() match {
      case begin @ Begin =>
        logger.warn("Should be impossible")

      case error: HandshakeError =>
        logger.warn("Should be impossible?")

      case error: TerminalError =>
        logger.warn("Should be impossible")
      case handshaked: Handshaked =>
        if (!state.compareAndSet(handshaked, handshaked.error(s"Msg type '${msgType}' not recognized.")))
          msgTypeNotRecognized(msgType)
    }
  }

  @tailrec
  private def chokeAndInterested(msgSize: Long, action: String, newState: Handshaked => Handshaked): Unit = {
    state.get() match {
      case handshaked: Handshaked =>
        if (msgSize != 1L) {
          val msg = s"$action. Unexpected message size. Expected: 1 byte. Actual: $msgSize bytes."
          if (!state.compareAndSet(handshaked, handshaked.error(msg))) chokeAndInterested(msgSize, action, newState)
          else logger.warn(msg)
        } else {
          if (!state.compareAndSet(handshaked, newState(handshaked))) chokeAndInterested(msgSize, action, newState)
          else logger.info(action)
        }
      case otherState =>
        logger.warn(s"$action but current state is '$otherState'.")
    }
  }

  private def receiveHandshake(begin: TcpConnected.type, input: InputStream): Unit = {
    (for {
      protocolLength <- toEither(input.read(), err => begin.handshakeError("Error extracting protocol length"))
      protocolLengthValid <-
        if (protocolLength == -1) Left(begin.handshakeError(s"Protocol length is -1.")) else Right(protocolLength)
      protocol <- toEither(input.readNBytes(protocolLengthValid), err => begin.handshakeError(err.getMessage))
      protocolValidated <-
        if (protocol.length != protocolLengthValid) Left(begin.handshakeError("Protocol length not correct"))
        else Right(protocol)
      reservedBytes <-
        toEither(ByteBuffer.wrap(input.readNBytes(8)).getLong, err => begin.handshakeError(err.getMessage))
      infoHashRes <- toEither(input.readNBytes(20), err => begin.handshakeError(err.getMessage))
      _ <-
        if (java.util.Arrays.equals(infoHashRes, infoHash.bytes))
          Left(begin.handshakeError("InfoHash of request not matching response"))
        else Right(infoHashRes)
      peerId <- toEither(input.readNBytes(20), err => begin.handshakeError(err.getMessage))
      peerIdValid <-
        if (peerId.length != 20) Left(begin.handshakeError("PeerId of response not 20 bytes")) else Right(peerId)
    } yield begin.handShaked(
      reservedBytes,
      Hex.encodeHexString(peerIdValid),
      new String(protocolValidated, new UTF_8())
    )) match {
      case Left(value) =>
        logger.warn(s"Error receiving handshake: $value")
        if (!state.compareAndSet(begin, value))
          logger.info(s"OMG....: $value")
      case Right(value) =>
        logger.info(s"Success receiving handshake: $value")
        if (!state.compareAndSet(begin, value))
          logger.info(s"OMG....: $value")
    }
  }

  private def toEither[A, B](f: => A, error: Throwable => B): Either[B, A] =
    Try(f).toEither.left.map(error)
}

object ReadThread {

  private[peerprotocol] def apply(
      socket: Socket,
      state: AtomicReference[State],
      infoHash: InfoHash,
      peerAddress: SocketAddress,
      pieces: List[String]
  ): ReadThread =
    new ReadThread(socket, state, infoHash, peerAddress, pieces)
}
