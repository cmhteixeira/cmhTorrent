package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent.InfoHash
import com.cmhteixeira.bittorrent.tracker.ReaderThread.maximumUdpPacketSize
import org.slf4j.LoggerFactory

import java.net.{DatagramPacket, DatagramSocket, InetSocketAddress}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

private[tracker] class ReaderThread private (
    udpSocket: DatagramSocket,
    state: AtomicReference[Map[InfoHash, Foo]]
) extends Runnable {
  private val logger = LoggerFactory.getLogger("TrackerReader")

  override def run(): Unit = {
    val packet = new DatagramPacket(ByteBuffer.allocate(maximumUdpPacketSize).array(), maximumUdpPacketSize)
    Try(udpSocket.receive(packet)) match {
      case Failure(exception) =>
        logger.warn("Called receive on socket. Waiting 500 milliseconds and then continuing.", exception)
        Thread.sleep(500)
        run()
      case Success(_) =>
        processPacket(packet)
        run()
    }
  }

  private def processPacket(i: DatagramPacket): Unit = {
    val payloadSize = i.getLength
    if (payloadSize == 16) { // could be ConnectResponse
      ConnectResponse.deserialize(i.getData) match {
        case Left(error) =>
          val msg =
            s"Received packet from '${i.getSocketAddress}' with 16 bytes, but not possible to deserialize into an Connect response: '$error'."
          logger.warn(msg)
        case Right(connectResponse) =>
          logger.info(s"Received potential Connect response from '${i.getSocketAddress}'.")
          processConnect(i.getSocketAddress.asInstanceOf[InetSocketAddress], connectResponse, System.nanoTime())
      }
    } else if (payloadSize >= 20 && (payloadSize - 20) % 6 == 0) { // could be an AnnounceResponse
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
      logger.warn(
        s"Received packet with $payloadSize bytes from '${i.getSocketAddress}'. It does not fit the expectations of either a 'ConnectResponse' nor a 'AnnounceResponse'."
      )
  }

  @tailrec
  private def processConnect(origin: InetSocketAddress, connectResponse: ConnectResponse, timestamp: Long): Unit = {
    val currentState = state.get()
    val ConnectResponse(txnId, connectId) = connectResponse
    currentState.flatMap {
      case (infoHash, tiers @ Tiers(_)) => tiers.connectResponse(origin, connectResponse).map(a => (infoHash, tiers, a))
      case (_, Submitted) => List.empty
    }.toList match {
      case Nil => logger.warn(s"Received possible Connect response from '$origin', but no state across all torrents.")
      case all @ (one :: two :: other) => logger.warn(s"Omg... this shouldn't be happening")
      case (infoHash, tiers, ConnectSent(_, channel, _)) :: Nil =>
        val newState = currentState + (infoHash -> tiers.updateEntry(
          origin,
          ConnectReceived(connectResponse.connectionId, timestamp)
        ))
        if (!state.compareAndSet(currentState, newState)) processConnect(origin, connectResponse, timestamp)
        else {
          logger.info(
            s"Received Connect response from '$origin' for '$infoHash'. TxnId: '$txnId'. Connection Id: '$connectId'."
          )
          channel.success(())
        }
    }
  }

  @tailrec
  private def processAnnounce(origin: InetSocketAddress, announceResponse: AnnounceResponse): Unit = {
    val currentState = state.get()
    val AnnounceResponse(_, _, _, leechers, seeders, peers) = announceResponse
    currentState.flatMap {
      case (infoHash, tiers @ Tiers(_)) =>
        tiers.announceResponse(origin, announceResponse).map(a => (infoHash, tiers, a))
      case (_, Submitted) => List.empty
    }.toList match {
      case Nil => logger.warn(s"Received possible Announce response from '$origin', but no state across all torrents.")
      case all @ (one :: two :: other) => logger.warn(s"Omg... this shouldn't be happening")
      case (infoHash, tiers, AnnounceSent(txnId, _, _, _)) :: Nil =>
        val newState =
          currentState + (infoHash -> tiers.updateEntry(origin, AnnounceReceived(leechers, seeders, peers)))

        if (!state.compareAndSet(currentState, newState)) processAnnounce(origin, announceResponse)
        else
          logger.info(
            s"Received Announce response from '$origin' for torrent '$infoHash' with txnId. '$txnId': ${announceResponse.peers.size} peers."
          )
    }
  }
}

private[tracker] object ReaderThread {
  private val maximumUdpPacketSize = 65507

  def apply(
      socket: DatagramSocket,
      state: AtomicReference[Map[InfoHash, Foo]]
  ): ReaderThread = new ReaderThread(socket, state)
}
