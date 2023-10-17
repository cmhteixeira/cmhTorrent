package com.cmhteixeira.bittorrent.tracker.impl

import com.cmhteixeira.bittorrent.tracker.impl.ReaderThread.maximumUdpPacketSize
import com.cmhteixeira.bittorrent.tracker.{AnnounceResponse, ConnectResponse}
import org.slf4j.LoggerFactory

import java.net.{DatagramPacket, DatagramSocket, InetSocketAddress}
import java.nio.ByteBuffer
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

private[impl] class ReaderThread private(
    udpSocket: DatagramSocket,
    handler: ReaderThread.Handler
) extends Runnable {
  private val logger = LoggerFactory.getLogger("TrackerListener")

  @tailrec
  override final def run(): Unit = {
    val packet = new DatagramPacket(ByteBuffer.allocate(maximumUdpPacketSize).array(), maximumUdpPacketSize)
    Try(udpSocket.receive(packet)) match {
      case Failure(exception) =>
        logger.warn("Receiving from socket. Thread will exit.", exception)
        handler.onError(exception.asInstanceOf[Exception])
      // todo: How to close this?
      case Success(_) =>
        processPacket(packet)
        run()
    }
  }

  private def processPacket(dg: DatagramPacket): Unit = {
    val payloadSize = dg.getLength
    val origin = dg.getSocketAddress.asInstanceOf[InetSocketAddress]
    if (payloadSize == 16) // could be ConnectResponse
      ConnectResponse.deserialize(dg.getData) match {
        case Left(e) =>
          logger.warn(s"Received 16 bytes packet from '$origin' but deserialization to connect response failed: '$e'.")
        case Right(connectResponse) => handler.onConnectReceived(connectResponse, origin, System.nanoTime())
      }
    else if (payloadSize >= 20 && (payloadSize - 20) % 6 == 0) // could be an AnnounceResponse
      AnnounceResponse.deserialize(dg.getData, payloadSize) match {
        case Left(e) =>
          logger.warn(
            s"Received $payloadSize bytes packet from '$origin' but deserialization to announce response failed: '$e'."
          )
        case Right(announceResponse) => handler.onAnnounceReceived(announceResponse, origin)
      }
    else
      logger.warn(
        s"Received $payloadSize bytes from '$origin'. Does not conform to 'ConnectResponse' or 'AnnounceResponse'."
      )
  }
}

private[impl] object ReaderThread {
  private val maximumUdpPacketSize = 65507

  trait Handler {

    def onError(exception: Exception): Unit
    def onConnectReceived(connectResponse: ConnectResponse, origin: InetSocketAddress, timestampNanos: Long)
    def onAnnounceReceived(announceResponse: AnnounceResponse, origin: InetSocketAddress): Unit

  }

  def apply(socket: DatagramSocket, handler: Handler): ReaderThread = new ReaderThread(socket, handler)
}
