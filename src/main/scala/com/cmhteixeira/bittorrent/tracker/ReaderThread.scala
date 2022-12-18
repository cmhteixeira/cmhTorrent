package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent.InfoHash
import com.cmhteixeira.bittorrent.tracker.ReaderThread.maximumUdpPacketSize
import org.slf4j.LoggerFactory

import java.net.{DatagramPacket, DatagramSocket, InetSocketAddress}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

class ReaderThread private (
    udpSocket: DatagramSocket,
    trackers: AtomicReference[Map[InfoHash, Tiers]]
) extends Runnable {
  private val logger = LoggerFactory.getLogger(getClass)

  override def run(): Unit = {
    val packet = new DatagramPacket(ByteBuffer.allocate(maximumUdpPacketSize).array(), maximumUdpPacketSize)
    Try(udpSocket.receive(packet)) match {
      case Failure(exception) =>
        logger.warn("Called receive on socket. Waiting 500 milliseconds and then continuing.", exception)
        Thread.sleep(500)
        run()
      case Success(_) =>
        logger.info("Received a new udp packet.")
        processPacket(packet)
        run()
    }
  }

  private def processPacket(i: DatagramPacket): Unit = {
    val payloadSize = i.getLength
    if (payloadSize == 16) { // could be ConnectResponse
      logger.info(s"Packet is $payloadSize bytes. Could be Connect response.")
      ConnectResponse.deserialize(i.getData) match {
        case Left(error) =>
          val msg =
            s"Received packet from '${i.getSocketAddress}' with 16 bytes, but not possible to deserialize into an Connect response: '$error'"
          logger.warn(msg)
        case Right(connectResponse) =>
          logger.info(s"Received potential Connect response from '${i.getSocketAddress}'")
          processConnect(i.getSocketAddress.asInstanceOf[InetSocketAddress], connectResponse, System.nanoTime())
      }
    } else if ((payloadSize - 20) % 6 == 0) { // could be an AnnounceResponse
      AnnounceResponse.deserialize(i.getData, payloadSize) match {
        case Left(value) =>
          val msg =
            s"Received packet from '${i.getSocketAddress}' with $payloadSize bytes, but couldn't be deserialized into an Announce response: '$value'."
          logger.warn(msg)
        case Right(announceResponse) =>
          logger.info(s"Received potential Announce response from '${i.getSocketAddress}'")
          processAnnounce(i.getSocketAddress.asInstanceOf[InetSocketAddress], announceResponse)
      }
    } else
      logger.warn("Packet does not fit the expectations of either a 'ConnectResponse' nor a 'AnnounceResponse'")
  }

  @tailrec
  private def processConnect(origin: InetSocketAddress, connectResponse: ConnectResponse, timestamp: Long): Unit = {
    val currentState = trackers.get()
    currentState
      .map {
        case (hash, tiers) =>
          tiers.possibleConnectRes(origin, connectResponse, timestamp).map(newState => (hash, newState))
      }
      .flatten
      .toList match {
      case Nil => logger.warn(s"Received possible connect response from '$origin', but no state across all torrents")
      case (infoHash, (ConnectSent(_, channel), tiers)) :: Nil =>
        val newState = currentState + (infoHash -> tiers)
        if (!trackers.compareAndSet(currentState, newState)) processConnect(origin, connectResponse, timestamp)
        else {
          logger.info(
            s"Received Connect response from '$origin' for torrent '$infoHash'. Transaction id: '${connectResponse.transactionId}'.Connection id: '${connectResponse.connectionId}'."
          )
          channel.success(())
        }
      case head :: more =>
        logger.error("omg....this is a big error") // todo. handle this better? This would have meant a txnId collision?
    }
  }

  @tailrec
  private def processAnnounce(origin: InetSocketAddress, announceResponse: AnnounceResponse): Unit = {
    val currentState = trackers.get()
    currentState
      .map {
        case (hash, tiers) =>
          tiers.possibleAnnounceResponse(origin, announceResponse).map(newState => (hash, newState))
      }
      .flatten
      .toList match {
      case Nil => logger.warn(s"Received possible connect response from $origin, but no state across all torrents")
      case (head @ (infoHash, _)) :: Nil =>
        val newState = currentState + head
        if (!trackers.compareAndSet(currentState, newState)) processAnnounce(origin, announceResponse)
        else logger.info(s"Received Announce response from '$origin' for torrent '$infoHash'")
      case head :: more =>
        logger.error("omg....this is a big error") // todo. handle this better? This would have meant a txnId collision?
    }
  }
}

object ReaderThread {
  private val maximumUdpPacketSize = 65507

  def apply(
      socket: DatagramSocket,
      state: AtomicReference[Map[InfoHash, Tiers]]
  ): ReaderThread = new ReaderThread(socket, state)
}
