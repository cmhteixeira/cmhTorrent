package com.cmhteixeira.bittorrent.peerprotocol

import com.cmhteixeira.bittorrent.InfoHash
import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import com.cmhteixeira.bittorrent.peerprotocol.State.BlockState.{Received, Sent}
import com.cmhteixeira.bittorrent.peerprotocol.State.TerminalError._
import com.cmhteixeira.bittorrent.peerprotocol.State._
import org.apache.commons.codec.binary.Hex
import org.slf4j.{Logger, LoggerFactory}
import scodec.bits.ByteVector

import java.io.InputStream
import java.net.{InetSocketAddress, Socket}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

private[peerprotocol] class ReadThread private (
    socket: Socket,
    state: AtomicReference[State],
    infoHash: InfoHash,
    peerAddress: InetSocketAddress,
    pieces: Int
) extends Runnable {
  val logger: Logger = LoggerFactory.getLogger("PeerReader." + s"${peerAddress.getHostString}:${peerAddress.getPort}")

  @tailrec
  override final def run(): Unit = {
    state.get() match {
      case Begin =>
        logger.error("This state should be impossible to be seen from the ReadThread. Exiting.")
        setError(ReadThreadSeesBeginState)
      case tcpConnected @ TcpConnected(_) =>
        receiveHandshake(tcpConnected, socket.getInputStream)
        run()
      case _: Handshaked =>
        Try(receiveNonHandshakeMessage()) match {
          case Failure(exception) =>
            logger.warn("Error receiving non-handshake message. Exiting and setting state to an error.", exception)
            setError(ReceivingMsg(exception))
          case Success(_) => run()
        }
      case error: TerminalError => logger.warn(s"State is in terminal error. Exiting. Error: '${error.error}'")
    }
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
          logger.info("???") //todo: Check if usage of try-complete is appropriate
          handshaked.me.requests.collect {
            case (_, Sent(promiseCompletion)) =>
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

  private def receiveNonHandshakeMessage(): Unit = {
    val msgTypeResponse = socket.getInputStream.readNBytes(4)
    if (msgTypeResponse.length != 4) setError(ReadBadNumberBytes(4, msgTypeResponse.length))
    else {
      val msgSizeLong = ByteBuffer.allocate(8).putInt(0).put(msgTypeResponse).clear().getLong
      if (msgSizeLong > Int.MaxValue) {
        logger.warn(s"Received message with $msgSizeLong bytes. Not supported.")
        socket.getInputStream.readNBytes(Int.MaxValue)
        socket.getInputStream.readNBytes((msgSizeLong - Int.MaxValue).toInt)
      } else if (msgSizeLong < 0L) logger.warn(s"Received message with negative size of $msgSizeLong.")
      else if (msgSizeLong == 0L) logger.info(s"Keep alive message.")
      else receiveNonHandshakeMessage(msgSizeLong.toInt) // todo: this is safe at this point....but allow for bigger
    }
  }

  private def receiveNonHandshakeMessage(msgSize: Int): Unit = {
    val msgType = socket.getInputStream.read()
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

  private def cancel(msgSize: Int): Unit = {
    logger.info("Received 'cancel' message")
    if (msgSize != 13) {
      logger.warn(s"'request' message has an unexpected size. Expected: 13 bytes. Actual $msgSize.")
      setError(UnexpectedMsgSize("cancel", 13, msgSize))
    } else {
      val payload = socket.getInputStream.readNBytes(12)
      if (payload.length != 12) setError(ReadBadNumberBytes(12, payload.length))
      else {
        val pieceIndex = ByteBuffer.allocate(8).putInt(0).put(payload, 0, 4).clear().getLong
        val byteOffset = ByteBuffer.allocate(8).putInt(0).put(payload, 4, 4).clear().getLong
        val length = ByteBuffer.allocate(8).putInt(0).put(payload, 8, 4).clear().getLong
        logger.info(s"Peer has cancelled request of $length bytes starting at $byteOffset from piece $pieceIndex.")
      }
    }
  }

  private def piece(msgSize: Int): Unit = {
    logger.info(s"Received a 'piece' message. Message size: $msgSize.")
    val payloadSize = msgSize - 1
    val blockSize = msgSize - 9
    val payload = socket.getInputStream.readNBytes(payloadSize)
    if (payload.length != payloadSize) {
      logger.warn(s"Received 'piece' block. Payload size: $payloadSize bytes. But there was an error.")
      setError(ReadBadNumberBytes(payloadSize, payload.length))
    } else {
      val pieceIndex = ByteBuffer.allocate(8).putInt(0).put(payload, 0, 4).clear().getLong.toInt
      val byteOffset = ByteBuffer.allocate(8).putInt(0).put(payload, 4, 4).clear().getLong.toInt
      val pieceBlock = ByteBuffer.allocate(blockSize).put(payload, 8, blockSize).clear().array()
      logger.info(s"Received block for piece $pieceIndex. Offset: $byteOffset, Size: $blockSize.")
      appendBlock(pieceIndex, byteOffset, pieceBlock)
    }
  }

  @tailrec
  private def appendBlock(pieceIndex: Int, offSet: Int, block: Array[Byte]): Unit = {
    val currentState = state.get()
    currentState match {
      case handshaked @ Handshaked(_, _, _, MyState(_, requests), _, _) =>
        val blockRequest = BlockRequest(pieceIndex, offSet, block.length)
        requests.get(blockRequest) match {
          case Some(Sent(channel)) =>
            val newState = handshaked.received(blockRequest)
            if (!state.compareAndSet(currentState, newState)) appendBlock(pieceIndex, offSet, block)
            else channel.trySuccess(ByteVector(block)) //todo: Check if usage of try-complete is appropriate
          case Some(Received) => logger.warn("Weird....")
          case None =>
            logger.warn(s"Received unregistered block for piece $pieceIndex. Offset: $offSet, length: ${block.length}")
        }
      case state => logger.warn(s"Appending block to piece sadas but state is $state.")
    }
  }

  private def request(msgSize: Int): Unit = {
    logger.info("Received 'request' message")
    if (msgSize != 13) {
      logger.warn(s"'request' message has an unexpected size. Expected: 13 bytes. Actual $msgSize.")
      setError(UnexpectedMsgSize("request", 13, msgSize))
    } else {
      val payload = socket.getInputStream.readNBytes(12)
      if (payload.length != 12) setError(ReadBadNumberBytes(12, payload.length))
      else {
        val pieceIndex = ByteBuffer.allocate(8).putInt(0).put(payload, 0, 4).clear().getLong
        val byteOffset = ByteBuffer.allocate(8).putInt(0).put(payload, 4, 4).clear().getLong
        val length = ByteBuffer.allocate(8).putInt(0).put(payload, 8, 4).clear().getLong
        logger.info(s"Peer has request $length bytes starting at $byteOffset from piece $pieceIndex.")
      }
    }
  }

  private def have(msgSize: Int): Unit = {
    logger.info(s"Received 'have' message")
    if (msgSize != 5) {
      logger.warn(s"'have' message has an unexpected size. Expected: 4 bytes. Actual $msgSize.")
      setError(UnexpectedMsgSize("have", 5, msgSize))
    } else {
      val payload = socket.getInputStream.readNBytes(4)
      if (payload.length != 4) setError(ReadBadNumberBytes(4, payload.length))
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
        val newState = handshaked.pierHasPiece(index)
        if (!state.compareAndSet(handshaked, newState)) addPeerPiece(index)
        else ()
      case _ => () //todo fix.
    }
  }

  private def receiveBitfield(msgSize: Int): Unit = {
    logger.info(s"Received 'bitfield' message")
    val expectedNumberBytes = math.ceil(pieces.toDouble / 8d)
    val bitFieldSize = msgSize - 1
    if (bitFieldSize != expectedNumberBytes) {
      logger.warn(s"Expected number of bytes in 'bitfield' message: $expectedNumberBytes. Actual: $bitFieldSize.")
      setError(UnexpectedMsgSize("bitfield", expectedNumberBytes.toInt, bitFieldSize))
    }

    val bitField = ByteBuffer.allocate(bitFieldSize).array()
    socket.getInputStream.readNBytes(bitField, 0, bitField.length)

    val peerPieces = (0 until pieces).map { index =>
      val byteIndex = math.floor(index * 1d / 8d).toInt
      val theByte = bitField(byteIndex)
      val bitIndexWithinByte = index % 8
      val isSet = (theByte & (1.toByte << (7 - bitIndexWithinByte))) != 0
      isSet
    }.toList

    logger.info(s"'bitfield' message: Peer has ${peerPieces.count(identity)} pieces.")
    setPeerPieces(peerPieces)
  }

  @tailrec
  private def setPeerPieces(in: List[Boolean]): Unit =
    state.get() match {
      case handshaked: Handshaked =>
        val newState = handshaked.peerPieces(in)
        if (!state.compareAndSet(handshaked, newState)) setPeerPieces(in)
        else ()
      case state => logger.warn(s"Setting peer pieces, but state is '$state'.")
    }

  private def msgTypeNotRecognized(msgType: Int): Unit = {
    logger.info(s"Received message of type $msgType, which is not recognized.")
    setError(MsgTypeNotRecognized(msgType))
  }

  @tailrec
  private def chokeAndInterested(msgSize: Int, action: String, newState: Handshaked => Handshaked): Unit = {
    state.get() match {
      case handshaked: Handshaked =>
        if (msgSize != 1L) {
          val msg = s"$action. Unexpected message size. Expected: 1 byte. Actual: $msgSize bytes."
          if (!state.compareAndSet(handshaked, handshaked.error(UnexpectedMsgSize("choke/interested", 1, msgSize))))
            chokeAndInterested(msgSize, action, newState)
          else logger.warn(msg)
        } else {
          if (!state.compareAndSet(handshaked, newState(handshaked))) chokeAndInterested(msgSize, action, newState)
          else logger.info(action)
        }
      case otherState =>
        logger.warn(s"$action but current state is '$otherState'.")
    }
  }

  private def receiveHandshake(begin: TcpConnected, input: InputStream): Unit =
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
          Right(infoHashRes)
        else Left(begin.handshakeError("InfoHash of request not matching response"))
      peerId <- toEither(input.readNBytes(20), err => begin.handshakeError(err.getMessage))
      peerIdValid <-
        if (peerId.length != 20) Left(begin.handshakeError("PeerId of response not 20 bytes")) else Right(peerId)
    } yield begin.handShaked(
      reservedBytes,
      Hex.encodeHexString(peerIdValid),
      new String(protocolValidated, StandardCharsets.UTF_8),
      pieces
    )) match {
      case Left(value) =>
        logger.error(s"Error receiving handshake: $value")
        setError(value.error)
      case Right(value) =>
        if (!state.compareAndSet(begin, value)) logger.error("This should positively never happen.")
        else begin.channel.success(())
    }

  private def toEither[A, B](f: => A, error: Throwable => B): Either[B, A] =
    Try(f).toEither.left.map(error)
}

object ReadThread {

  private[peerprotocol] def apply(
      socket: Socket,
      state: AtomicReference[State],
      infoHash: InfoHash,
      peerAddress: InetSocketAddress,
      pieces: Int
  ): ReadThread =
    new ReadThread(socket, state, infoHash, peerAddress, pieces)
}
