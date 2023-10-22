package com.cmhteixeira.bittorrent.tracker

import cats.Show

import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteBuffer

private[tracker] case class AnnounceResponse(
    action: Int,
    transactionId: Int,
    interval: Int,
    leechers: Int,
    seeders: Int,
    peers: List[InetSocketAddress]
)

private[tracker] object AnnounceResponse {

  private def extractPeers(
      numPeersRemaining: Int,
      in: ByteBuffer,
      accumulatedPeers: List[InetSocketAddress]
  ): List[InetSocketAddress] = {
    if (numPeersRemaining == 0) accumulatedPeers
    else {
      val ip = ByteBuffer.allocate(4)
      in.get(ip.array(), 0, 4)

      val port: Int = in.getChar

      extractPeers(
        numPeersRemaining - 1,
        in,
        accumulatedPeers :+ new InetSocketAddress(InetAddress.getByAddress(ip.array()), port)
      )
    }
  }

  private def deserializeInternal(
      in: Array[Byte],
      numberPeers: Int
  ): Either[String, AnnounceResponse] = {

    val response = ByteBuffer.wrap(in)
    val action = ByteBuffer.allocate(4)
    val transactionId = ByteBuffer.allocate(4)
    val interval = ByteBuffer.allocate(4)
    val leechers = ByteBuffer.allocate(4)
    val seeders = ByteBuffer.allocate(4)

    response
      .get(action.array(), 0, 4)
      .get(transactionId.array(), 0, 4)
      .get(interval.array(), 0, 4)
      .get(leechers.array(), 0, 4)
      .get(seeders.array(), 0, 4)

    Right(
      AnnounceResponse(
        action.getInt,
        transactionId.getInt,
        interval.getInt,
        leechers.getInt,
        seeders.getInt,
        extractPeers(numberPeers, response, List.empty)
      )
    )
  }

  def deserialize(in: Array[Byte], sizePacket: Int): Either[String, AnnounceResponse] =
    if (sizePacket < 20) Left("Received packet had less than 20 bytes. Couldn't possibly be valid Announce response.")
    else if ((sizePacket - 20) % 6 != 0) Left("Received packet bla bla bla")
    else deserializeInternal(in, (sizePacket - 20) / 6)


  implicit val show: Show[AnnounceResponse] = new Show[AnnounceResponse] {

    override def show(t: AnnounceResponse): String =
      s"""Announce Response
        | Action: ${t.action}
        | TransactionId: ${t.transactionId}
        | Interval: ${t.interval}
        | Number leechers: ${t.leechers}
        | Number seeders: ${t.seeders}
        | Peers:
        |   [${t.peers.map { case socket => s"${socket.getHostName}:${socket.getPort}" }.mkString("\n   ")}]
        |""".stripMargin
  }
}
