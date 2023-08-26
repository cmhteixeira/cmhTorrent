package com.cmhteixeira.bittorrent.tracker.core

import com.cmhteixeira.bittorrent.InfoHash
import com.cmhteixeira.bittorrent.tracker.TrackerState.{AnnounceSent, ConnectSent}
import com.cmhteixeira.bittorrent.tracker.core.ReaderThread.maximumUdpPacketSize
import com.cmhteixeira.bittorrent.tracker.{AnnounceResponse, ConnectResponse, TrackerState}
import org.slf4j.LoggerFactory

import java.net.{DatagramPacket, DatagramSocket, InetSocketAddress}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
private[tracker] class ReaderThread private (
    udpSocket: DatagramSocket,
    state: AtomicReference[Map[InfoHash, Map[InetSocketAddress, TrackerState]]]
) extends Runnable {
  private val logger = LoggerFactory.getLogger("TrackerReader")

  @tailrec
  override final def run(): Unit = {
    val packet = new DatagramPacket(ByteBuffer.allocate(maximumUdpPacketSize).array(), maximumUdpPacketSize)
    Try(udpSocket.receive(packet)) match {
      case Failure(exception) =>
        logger.warn("Called receive on socket. Waiting 500 milliseconds and then continuing.", exception)
        Thread.sleep(500)
        run()
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
        case Right(connectResponse) =>
          logger.info(s"Received potential Connect response from '$origin'.")
          processConnect(origin, connectResponse, System.nanoTime())
      }
    else if (payloadSize >= 20 && (payloadSize - 20) % 6 == 0) // could be an AnnounceResponse
      AnnounceResponse.deserialize(dg.getData, payloadSize) match {
        case Left(e) =>
          logger.warn(
            s"Received $payloadSize bytes packet from '$origin' but deserialization to announce response failed: '$e'."
          )
        case Right(announceResponse) =>
          logger.info(s"Received potential Announce response from '$origin' with $payloadSize bytes.")
          processAnnounce(origin, announceResponse)
      }
    else
      logger.warn(
        s"Received $payloadSize bytes packet from '$origin'. Does not conform to 'ConnectResponse' or 'AnnounceResponse'."
      )
  }

  private def processConnect(origin: InetSocketAddress, connectResponse: ConnectResponse, timestamp: Long): Unit = {
    val currentState = state.get()
    val ConnectResponse(txnId, connectId) = connectResponse
    currentState.toList.flatMap { case (hash, underlying) =>
      underlying.get(origin) match {
        case Some(conSent @ ConnectSent(txnId, _)) if txnId == connectResponse.transactionId => List(hash -> conSent)
        case _ => List.empty
      }
    } match {
      case Nil => logger.warn(s"Received possible Connect response from '$origin', but no state across all torrents.")
      case (infoHash, ConnectSent(_, channel)) :: Nil =>
        logger.info(s"Matched Connect response: Torrent=$infoHash,tracker=$origin,txdId=$txnId,connId=$connectId")
        channel.trySuccess((connectResponse, timestamp))
      case xs =>
        logger.warn(
          s"Connect response (txdId=${connectResponse.transactionId}) matches more than 1 torrent: [${xs.map(_._1).mkString(", ")}]."
        )
    }
  }

  private def processAnnounce(origin: InetSocketAddress, announceResponse: AnnounceResponse): Unit = {
    val currentState = state.get()
    val AnnounceResponse(_, _, _, _, _, peers) = announceResponse
    currentState.flatMap { case (infoHash, state4Torrent) =>
      state4Torrent
        .map { case (address, state) => address -> state }
        .toList
        .collectFirst {
          case (thisTrackerSocket, announceSent @ AnnounceSent(txnId, _, _))
              if thisTrackerSocket == origin && txnId == announceResponse.transactionId =>
            announceSent
        }
        .map(a => (infoHash, state4Torrent, a))
    }.toList match {
      case Nil => logger.warn(s"Received possible Announce response from '$origin', but no state across all torrents.")
      case all @ (one :: two :: other) => logger.warn(s"Omg... this shouldn't be happening")
      case (infoHash, tiers, AnnounceSent(txnId, _, channel)) :: Nil if txnId == announceResponse.transactionId =>
        logger.info(s"Announce response from '$origin' for '$infoHash' with txnId '$txnId': ${peers.size} peers.")
        channel.trySuccess(announceResponse)
      case (infoHash, tiers, AnnounceSent(txnId, _, channel)) :: Nil => logger.warn("Bla blabla")
    }
  }
}

private[tracker] object ReaderThread {
  private val maximumUdpPacketSize = 65507

  def apply(
      socket: DatagramSocket,
      state: AtomicReference[Map[InfoHash, Map[InetSocketAddress, TrackerState]]]
  ): ReaderThread = new ReaderThread(socket, state)
}
