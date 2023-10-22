package com.cmhteixeira.bittorrent.peerprotocol

import com.cmhteixeira.bittorrent.InfoHash
import com.cmhteixeira.bittorrent.peerprotocol.Peer.Subscriber
import com.cmhteixeira.bittorrent.peerprotocol.PeerImpl.Message
import com.cmhteixeira.bittorrent.peerprotocol.PeerImpl.State.{Closed, _}
import org.apache.commons.codec.binary.Hex
import org.slf4j.{Logger, LoggerFactory}
import scodec.bits.{BitVector, ByteVector}
import java.io.InputStream
import java.net.{InetSocketAddress, Socket}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

private[peerprotocol] class ReadThread private (
    socket: Socket,
    infoHash: InfoHash,
    peerAddress: InetSocketAddress,
    pieces: Int,
    handler: PeerImpl.Handler
) extends Runnable {
  val logger: Logger = LoggerFactory.getLogger("PeerReader." + s"${peerAddress.getHostString}:${peerAddress.getPort}")

  @tailrec
  override final def run(): Unit = {
    handler.getState match {
      case Unsubscribed =>
        handler.message(Message.error("Reading from peer when still unsubscribed should be impossible."))
      case Closed => ()
      case Unconnected(subscriber, _) =>
        handler.message(receiveHandshake(subscriber, socket.getInputStream))
        run()
      case _: Handshaked =>
        Try(receiveNonHandshakeMessage()) match {
          case Failure(exception) =>
            handler.message(Message.Error(new Exception("Receiving normal message.", exception)))
          case Success(message) =>
            handler.message(message)
            run()
        }
    }
  }

  private def receiveNonHandshakeMessage(): Message = {
    val msgTypeResponse = socket.getInputStream.readNBytes(4)
    if (msgTypeResponse.length != 4) Message.error("TODO")
    else {
      val msgSizeLong = ByteBuffer.allocate(8).putInt(0).put(msgTypeResponse).clear().getLong
      if (msgSizeLong > Int.MaxValue) {
        socket.getInputStream.readNBytes(Int.MaxValue)
        socket.getInputStream.readNBytes((msgSizeLong - Int.MaxValue).toInt)
        Message.error(s"Received message with $msgSizeLong bytes. Not supported.")
      } else if (msgSizeLong < 0L) Message.error(s"Received message with negative size of $msgSizeLong.")
      else if (msgSizeLong == 0L) Message.KeepAlive
      else receiveNonHandshakeMessage(msgSizeLong.toInt) // todo: this is safe at this point....but allow for bigger
    }
  }

  private def receiveNonHandshakeMessage(msgSize: Int): Message = {
    val msgType = socket.getInputStream.read()
    if (msgType == 0) chokeAndInterested(msgSize, "Choke", Message.Choke)
    else if (msgType == 1) chokeAndInterested(msgSize, "Unchoke", Message.UnChoke)
    else if (msgType == 2) chokeAndInterested(msgSize, "Interested", Message.Interested)
    else if (msgType == 3) chokeAndInterested(msgSize, "Not interested", Message.Uninterested)
    else if (msgType == 4) have(msgSize)
    else if (msgType == 5) receiveBitfield(msgSize)
    else if (msgType == 6) request(msgSize)
    else if (msgType == 7) piece(msgSize)
    else if (msgType == 8) cancel(msgSize)
    else Message.MessageTypeUnknown(msgType)
  }

  private def cancel(msgSize: Int): Message = {
    logger.info("Received 'cancel' message")
    if (msgSize != 13) Message.error(s"'cancel' has unexpected size. Expected: 13 bytes. Actual: $msgSize.")
    else {
      val payload = socket.getInputStream.readNBytes(12)
      if (payload.length != 12) Message.error(s"'cancel' payload does not have 12 bytes: ${payload.length}.")
      else {
        val pieceIndex = ByteBuffer.allocate(8).putInt(0).put(payload, 0, 4).clear().getLong
        val byteOffset = ByteBuffer.allocate(8).putInt(0).put(payload, 4, 4).clear().getLong
        val length = ByteBuffer.allocate(8).putInt(0).put(payload, 8, 4).clear().getLong
        logger.info(s"Peer has cancelled request of $length bytes starting at $byteOffset from piece $pieceIndex.")
        Message.Cancel(pieceIndex.toInt, byteOffset.toInt, length.toInt) // todo: Fix casts
      }
    }
  }

  private def piece(msgSize: Int): Message = {
    logger.info(s"Received a 'piece' message. Message size: $msgSize.")
    val payloadSize = msgSize - 1
    val blockSize = msgSize - 9
    val payload = socket.getInputStream.readNBytes(payloadSize)
    if (payload.length != payloadSize)
      Message.error(s"Received 'piece' block. Payload size: $payloadSize bytes. But there was an error.")
    else { // todo: fix casts.
      val pieceIndex = ByteBuffer.allocate(8).putInt(0).put(payload, 0, 4).clear().getLong.toInt
      val byteOffset = ByteBuffer.allocate(8).putInt(0).put(payload, 4, 4).clear().getLong.toInt
      val pieceBlock = ByteBuffer.allocate(blockSize).put(payload, 8, blockSize).clear().array()
      logger.info(s"Received block for piece $pieceIndex. Offset: $byteOffset, Size: $blockSize.")
      Message.Piece(pieceIndex, byteOffset, ByteVector(pieceBlock))
    }
  }

  private def request(msgSize: Int): Message = {
    logger.info("Received 'request' message")
    if (msgSize != 13) Message.error(s"'request' message has an unexpected size. Expected: 13 bytes. Actual $msgSize.")
    else {
      val payload = socket.getInputStream.readNBytes(12)
      if (payload.length != 12) Message.error(s"'request' message. Unable to read 12 bytes. ReadL ${payload.length}")
      else {
        val pieceIndex = ByteBuffer.allocate(8).putInt(0).put(payload, 0, 4).clear().getLong
        val byteOffset = ByteBuffer.allocate(8).putInt(0).put(payload, 4, 4).clear().getLong
        val length = ByteBuffer.allocate(8).putInt(0).put(payload, 8, 4).clear().getLong
        Message.Request(pieceIndex.toInt, byteOffset.toInt, length.toInt) // todo: fix casts.
      }
    }
  }

  private def have(msgSize: Int): Message = {
    logger.info(s"Received 'have' message")
    val errMsg = Message.error(s"'have' with unexpected size. Expected: 4 bytes. Actual $msgSize.")
    if (msgSize != 5) errMsg
    else {
      val payload = socket.getInputStream.readNBytes(4)
      if (payload.length != 4) errMsg
      else {
        val pieceIndex = ByteBuffer.allocate(8).putInt(0).put(payload).clear().getLong
        logger.info(s"Peer has piece $pieceIndex")
        Message.HasPiece(pieceIndex.toInt) // todo: Fix cast.
      }
    }
  }

  private def receiveBitfield(msgSize: Int): Message = {
    logger.info(s"Received 'bitfield' message")
    val expected = math.ceil(pieces.toDouble / 8d)
    val bitFieldSize = msgSize - 1
    if (bitFieldSize != expected)
      Message.error(s"Expected number of bytes in 'bitfield' message: $expected. Actual: $bitFieldSize.")
    else {
      val bitField = ByteBuffer.allocate(bitFieldSize).array()
      socket.getInputStream.readNBytes(bitField, 0, bitField.length)

      val peerPieces = (0 until pieces).map { index =>
        val byteIndex = math.floor(index * 1d / 8d).toInt
        val theByte = bitField(byteIndex)
        val bitIndexWithinByte = index % 8
        val isSet = (theByte & (1.toByte << (7 - bitIndexWithinByte))) != 0
        isSet
      }.toList
      Message.HasPieces(peerPieces)
    }
  }

  private def chokeAndInterested(msgSize: Int, action: String, message: Message): Message =
    if (msgSize != 1L)
      Message.error(s"$action. Unexpected message size. Expected: 1 byte. Actual: $msgSize bytes.")
    else message

  private def receiveHandshake(s: Subscriber, input: InputStream): Message =
    (for {
      protocolLength <- Try(input.read())
      protocolLengthValid <-
        if (protocolLength == -1) Failure(new RuntimeException("Receiving handshake. Protocol length: -1"))
        else Success(protocolLength)
      protocol <- Try(input.readNBytes(protocolLengthValid))
      protocolValidated <-
        if (protocol.length != protocolLengthValid)
          Failure(new RuntimeException(s"Did not read expected ${protocolLengthValid}"))
        else Success(protocol)
      reservedBytes <-
        Try(ByteBuffer.wrap(input.readNBytes(8)).getLong)
      infoHashRes <- Try(input.readNBytes(20))
      _ <-
        if (java.util.Arrays.equals(infoHashRes, infoHash.bytes))
          Success(infoHashRes)
        else Failure(new RuntimeException(s"Handshake. Hash did not match."))
      peerId <- Try(input.readNBytes(20))
      peerIdValid <-
        if (peerId.length != 20) Failure(new RuntimeException(s"PeerId length not 20: ${peerId.length}"))
        else Success(peerId)
    } yield Handshaked(
      reservedBytes = reservedBytes,
      peerId = Hex.encodeHexString(peerIdValid),
      protocol = new String(protocolValidated, StandardCharsets.UTF_8),
      me = MyState(ConnectionState(isChocked = true, isInterested = false), Map.empty),
      peer = PeerState(ConnectionState(isChocked = true, isInterested = false), BitVector.fill(pieces)(high = false)),
      keepAliveTasks = None,
      subscriber = s
    )) match {
      case Failure(exception) => Message.Error(new Exception("Receiving handshake.", exception))
      case Success(value) => Message.ReceivedHandshake(value)
    }
}

object ReadThread {

  private[peerprotocol] def apply(
      socket: Socket,
      handler: PeerImpl.Handler,
      infoHash: InfoHash,
      peerAddress: InetSocketAddress,
      pieces: Int
  ): ReadThread =
    new ReadThread(socket, infoHash, peerAddress, pieces, handler)
}
