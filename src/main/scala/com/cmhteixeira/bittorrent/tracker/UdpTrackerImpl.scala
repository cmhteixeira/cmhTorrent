package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent.tracker.UdpTracker.{DeserializationError, IncorrectResponseTransactionId}
import com.cmhteixeira.bittorrent.{deserialize, serialize}
import org.slf4j.LoggerFactory

import java.net.{DatagramPacket, DatagramSocket, InetAddress}
import scala.util.{Failure, Success, Try}

class UdpTrackerImpl private (host: String, port: Int, timeoutMillis: Int, maxRetries: Short) extends UdpTracker {

  val logger = LoggerFactory.getLogger(getClass)

  override def connect(transactionId: Int): Either[UdpTracker.Error, UdpConnected] = {
    logger.info(s"Sending Connect. TransactionId: $transactionId")
    val socket = new DatagramSocket()
    socket.connect(InetAddress.getByName(host), port)
    socket.setSoTimeout(timeoutMillis)
    val connect = ConnectRequest(transactionId)

    val payload = serialize(connect)
    socket.send(new DatagramPacket(payload, payload.length))

    val responseByteArray = new Array[Byte](16)
    Try(
      socket.receive(new DatagramPacket(responseByteArray, responseByteArray.length))
    ) match {
      case Failure(error: java.net.SocketTimeoutException) =>
        logger.error("Timeout when receiving UDP package after sending Connect", error)
        Left(UdpTracker.NotReceived(timeoutMillis, maxRetries))
      case Success(_) =>
        deserialize[ConnectResponse](responseByteArray).left
          .map(DeserializationError)
          .flatMap { connectResponse =>
            if (connectResponse.transactionId == transactionId)
              Right(UdpConnectedImpl(socket, connectResponse))
            else Left(IncorrectResponseTransactionId(transactionId, connectResponse.transactionId))
          }
    }

  }
}

object UdpTrackerImpl {
  def apply(host: String, port: Int): UdpTrackerImpl = new UdpTrackerImpl(host, port, 10000, 3)
}
