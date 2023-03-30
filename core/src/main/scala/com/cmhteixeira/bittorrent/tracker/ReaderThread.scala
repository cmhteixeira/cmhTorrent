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
    state: AtomicReference[Map[InfoHash, State]]
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
    if (payloadSize == 16) // could be ConnectResponse
      ConnectResponse.deserialize(dg.getData) match {
        case Left(error) =>
          logger.warn(
            s"Received packet from '${dg.getSocketAddress}' with 16 bytes, but not possible to deserialize into an Connect response: '$error'."
          )
        case Right(connectResponse) =>
          logger.info(s"Received potential Connect response from '${dg.getSocketAddress}'.")
          processConnect(dg.getSocketAddress.asInstanceOf[InetSocketAddress], connectResponse, System.nanoTime())
      }
    else if (payloadSize >= 20 && (payloadSize - 20) % 6 == 0) // could be an AnnounceResponse
      AnnounceResponse.deserialize(dg.getData, payloadSize) match {
        case Left(value) =>
          logger.warn(
            s"Received packet from '${dg.getSocketAddress}' with $payloadSize bytes, but couldn't be deserialized into an Announce response: '$value'."
          )
        case Right(announceResponse) =>
          logger.info(s"Received potential Announce response from '${dg.getSocketAddress}' with $payloadSize bytes.")
          processAnnounce(dg.getSocketAddress.asInstanceOf[InetSocketAddress], announceResponse)
      }
    else
      logger.warn(
        s"Received packet with $payloadSize bytes from '${dg.getSocketAddress}'. It does not fit the expectations of either a 'ConnectResponse' nor a 'AnnounceResponse'."
      )
  }

  private def processConnect(origin: InetSocketAddress, connectResponse: ConnectResponse, timestamp: Long): Unit = {
    val currentState = state.get()
    val ConnectResponse(txnId, connectId) = connectResponse
    currentState.toList.flatMap {
      case (hash, Tiers(underlying, _)) =>
        underlying.get(origin) match {
          case Some(conSent @ ConnectSent(txnId, _)) if txnId == connectResponse.transactionId => List(hash -> conSent)
          case _ => List.empty
        }
      case _ => List.empty
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
    currentState.flatMap {
      case (infoHash, tiers @ Tiers(_, _)) =>
        tiers.announceResponse(origin, announceResponse).map(a => (infoHash, tiers, a))
      case (_, Submitted) => List.empty
    }.toList match {
      case Nil => logger.warn(s"Received possible Announce response from '$origin', but no state across all torrents.")
      case all @ (one :: two :: other) => logger.warn(s"Omg... this shouldn't be happening")
      case (infoHash, tiers, AnnounceSent(txnId, _, channel, _)) :: Nil if txnId == announceResponse.transactionId =>
        logger.info(s"Announce response from '$origin' for '$infoHash' with txnId '$txnId': ${peers.size} peers.")
        channel.trySuccess(announceResponse)
      case (infoHash, tiers, AnnounceSent(txnId, _, channel, _)) :: Nil => logger.warn("Bla blabla")
    }
  }
}

private[tracker] object ReaderThread {
  private val maximumUdpPacketSize = 65507

  def apply(
      socket: DatagramSocket,
      state: AtomicReference[Map[InfoHash, State]]
  ): ReaderThread = new ReaderThread(socket, state)
}
