package com.cmhteixeira.bittorrent.peerprotocol

import cats.implicits.toTraverseOps
import com.cmhteixeira.bittorrent.InfoHash
import com.cmhteixeira.bittorrent.peerprotocol.State.MyPieceState.{Asked, Blocks, Downloading, Have}
import com.cmhteixeira.bittorrent.peerprotocol.State.TerminalError.{
  MsgTypeNotRecognized,
  ReadBadNumberBytes,
  ReceivingMsg,
  UnexpectedMsgSize,
  WritingPieceToFile
}
import com.cmhteixeira.bittorrent.peerprotocol.State._
import org.apache.commons.codec.binary.Hex
import org.slf4j.{Logger, LoggerFactory, MDC}
import scodec.bits.ByteVector
import sun.nio.cs.UTF_8

import java.io.{IOException, InputStream}
import java.net.{InetSocketAddress, Socket, SocketAddress}
import java.nio.ByteBuffer
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

private[peerprotocol] class ReadThread private (
    socket: Socket,
    state: AtomicReference[State],
    infoHash: InfoHash,
    peerAddress: SocketAddress,
    pieces: Int,
    downloadDir: Path,
    pieceLength: Int
) extends Runnable {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  @tailrec
  override final def run(): Unit = {
    MDC.put("context", peerAddress.toString)
    state.get() match {
      case Begin =>
        logger.warn("THIS SHOULD BE IMPOSSIBLE ...... Not yet connected.")
        Thread.sleep(100)
        run()
      case tcpConnected @ TcpConnected =>
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
    val newState = currentState match {
      case goodState: Good => TerminalError(goodState, msg)
      case error: TerminalError => error
    }

    if (!state.compareAndSet(currentState, newState)) setError(msg)
    else {
      logger.info(s"Error '$msg' encountered. Closing connection")
      socket.close()
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
      else if (msgSizeLong == 0L) logger.info(s"Keep alive message from '${socket.getRemoteSocketAddress}'.")
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
      logger.info(s"Peer has sent $blockSize bytes starting at $byteOffset for piece $pieceIndex.")
      appendBlock(pieceIndex, byteOffset, pieceBlock)
    }
  }

  private def appendBlock(pieceIndex: Int, byteOffSet: Int, block: Array[Byte]): Unit = {
    val currentState = state.get()
    currentState match {
      case handshaked @ Handshaked(_, _, _, _, _, pieces) =>
        pieces.get(pieceIndex) match {
          case Some(PieceState(asked @ Asked(_), _)) =>
            val newState = handshaked.updateMyState(pieceIndex, asked.firstBlock(byteOffSet, ByteVector(block)))
            if (!state.compareAndSet(currentState, newState)) appendBlock(pieceIndex, byteOffSet, block)
            else logger.info(s"Downloaded block of piece $pieceIndex. Offset: $byteOffSet. Length: ${block.length}.")

          case Some(PieceState(downloading @ Downloading(channel, blocks), _)) =>
            blocks.append(byteOffSet, ByteVector(block)).assemble(pieceLength) match {
              case Some(entirePiece) =>
                writeFile(pieceIndex, entirePiece.toArray) match {
                  case Failure(exception) =>
                    val msg = s"Failed to write downloaded blocks of piece $pieceIndex into file."
                    logger.warn(msg, exception)
                    channel.failure(new IOException(msg, exception))
                    setError(WritingPieceToFile(pieceIndex, exception))

                  case Success(path) =>
                    val newState = handshaked.updateMyState(pieceIndex, Have(path))
                    if (!state.compareAndSet(currentState, newState)) appendBlock(pieceIndex, byteOffSet, block)
                    else {
                      logger.info(s"Finished downloading piece $pieceIndex.")
                      channel.success(path)
                    }
                }
              case None =>
                val newState =
                  handshaked.updateMyState(pieceIndex, downloading.anotherBlock(byteOffSet, ByteVector(block)))
                if (!state.compareAndSet(currentState, newState)) appendBlock(pieceIndex, byteOffSet, block)
                else
                  logger.info(s"Downloaded block of piece $pieceIndex. Offset: $byteOffSet. Length: ${block.length}.")
            }

          case Some(state) => logger.warn(s"Received piece block but state is: '$state'.")

          case None => logger.warn(s"Received unknown piece of index $pieceIndex")

        }
      case state => logger.warn(s"Appending block to piece sadas but state is $state.")
    }
  }

  private def writeFile(pieceIndex: Int, blocks: Array[Byte]): Try[Path] =
    Try(
      Files.write(
        generateFileName(pieceIndex),
        blocks,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING
      )
    )

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

  private def generateFileName(piece: Int): Path =
    downloadDir.resolve(s"${socket.getInetAddress.getHostAddress}.piece-$piece")

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
          Right(infoHashRes)
        else Left(begin.handshakeError("InfoHash of request not matching response"))
      peerId <- toEither(input.readNBytes(20), err => begin.handshakeError(err.getMessage))
      peerIdValid <-
        if (peerId.length != 20) Left(begin.handshakeError("PeerId of response not 20 bytes")) else Right(peerId)
    } yield begin.handShaked(
      reservedBytes,
      Hex.encodeHexString(peerIdValid),
      new String(protocolValidated, new UTF_8()),
      pieces
    )) match {
      case Left(value) =>
        logger.warn(s"Error receiving handshake: $value")
        if (!state.compareAndSet(begin, value))
          logger.info(s"OMG....: $value")
      case Right(value) =>
        logger.info(s"Success receiving handshake.")
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
      pieces: Int,
      downloadDir: Path,
      pieceLength: Int
  ): ReadThread =
    new ReadThread(socket, state, infoHash, peerAddress, pieces, downloadDir, pieceLength)
}
