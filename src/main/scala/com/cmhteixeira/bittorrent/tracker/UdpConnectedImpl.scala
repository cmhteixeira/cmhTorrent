package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent.tracker.UdpTracker.SomeRandomError
import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory

import java.net.{DatagramPacket, DatagramSocket}
import scala.util.{Failure, Success, Try}

class UdpConnectedImpl private (socket: DatagramSocket, connectResponse: ConnectResponse) extends UdpConnected {

  val logger = LoggerFactory.getLogger(getClass)

  override def announce(
      request: AnnounceRequest
  ): Either[UdpTracker.Error, AnnounceResponse] = {
    logger.info(
      s"Sending AnnounceRequest. TransactionId: ${request.transactionId}. InfoHash: ${Hex.encodeHexString(request.infoHash)}"
    )
    val payload = com.cmhteixeira.bittorrent.serialize(request)
    socket.send(new DatagramPacket(payload, payload.length))

    val responseByteArray =
      new Array[Byte](20 + request.numWanted * 6) // 20 for size core response + 12 bytes per peer.
    val responseDatagram = new DatagramPacket(responseByteArray, responseByteArray.length)
    Try(
      socket.receive(responseDatagram)
    ) match {
      case Failure(error: java.net.SocketTimeoutException) =>
        logger.error("Timeout when receiving UDP package after sending Connect", error)
        Left(SomeRandomError("Timeout"))
      case Success(_) =>
        logger.info(s"Received packet after announce request. Packet size: ${responseDatagram.getLength}")
        AnnounceResponse.deserilizeFoo(responseByteArray, responseDatagram.getLength) match {
          case Left(value) =>
            logger.error(s"No deseriazlion correct: ${value}")
            Left(SomeRandomError("asd"))
          case Right(value) =>
            logger.info(s"Deserialized Announce response: ${value}")
            Right(value)
        }
    }
  }
  override def connectionId: Long = connectResponse.connectionId
}

object UdpConnectedImpl {

  private[tracker] def apply(socket: DatagramSocket, connectResponse: ConnectResponse): UdpConnectedImpl =
    new UdpConnectedImpl(socket, connectResponse)
}
