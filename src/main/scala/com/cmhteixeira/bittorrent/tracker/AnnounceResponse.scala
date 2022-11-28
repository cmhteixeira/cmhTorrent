package com.cmhteixeira.bittorrent.tracker

import cats.Show
import com.cmhteixeira.bittorrent.Deserializer

import java.net.{Inet4Address, InetAddress}
import java.nio.ByteBuffer

sealed trait AnnounceResponse {
  def action: Int
  def transactionId: Int
  def interval: Int
  def leechers: Int
  def seeders: Int
  def peers: List[(AnnounceResponse.IpAddress, Int)]
}

object AnnounceResponse {
  sealed trait IpAddress
  case class IPv4(i: InetAddress) extends IpAddress
  case class IPv6(i: InetAddress) extends IpAddress

  private case class AnnounceResponseImpl(
      action: Int,
      transactionId: Int,
      interval: Int,
      leechers: Int,
      seeders: Int,
      peers: List[(AnnounceResponse.IpAddress, Int)]
  ) extends AnnounceResponse

  def apply(
      action: Int,
      transactionId: Int,
      interval: Int,
      leechers: Int,
      seeders: Int,
      peers: List[(AnnounceResponse.IpAddress, Int)]
  ): AnnounceResponse =
    AnnounceResponseImpl(
      action,
      transactionId,
      interval,
      leechers,
      seeders,
      peers
    )

  def deserilizeFoo(in: Array[Byte], sizePacket: Int) = {

    def extractPeers(
        numPeersRemaining: Int,
        in: ByteBuffer,
        accumulatedPeers: List[(IPv4, Int)]
    ): List[(IPv4, Int)] = {
      if (numPeersRemaining == 0) accumulatedPeers
      else {
        val ip = ByteBuffer.allocate(4)
        in.get(ip.array(), 0, 4)

        val port: Int = in.getChar

        extractPeers(
          numPeersRemaining - 1,
          in,
          accumulatedPeers :+ (IPv4(InetAddress.getByAddress(ip.array())), port)
        )

      }
    }

    def apply(
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
        AnnounceResponseImpl(
          action.getInt,
          transactionId.getInt,
          interval.getInt,
          leechers.getInt,
          seeders.getInt,
          extractPeers(numberPeers, response, List.empty)
        )
      )
    }

    if (sizePacket < 20) Left("Received packet had less than 20 bytes. Couldn't possibly be valid response")
    else if ((sizePacket - 20) % 6 != 0) Left("Received packet bla bla bla")
    else apply(in, (sizePacket - 20) / 6)
  }

  implicit val show: Show[AnnounceResponse] = new Show[AnnounceResponse] {

    override def show(t: AnnounceResponse): String =
      s"""Announce Response
        | Action: ${t.action}
        | TransactionId: ${t.transactionId}
        | Interval: ${t.interval}
        | Number leechers: ${t.leechers}
        | Number seeders: ${t.seeders}
        | Peers:
        |   [${t.peers.map { case (ip, port) => s"$ip:$port" }.mkString("\n   ")}]
        |""".stripMargin
  }
}

//Offset      Size            Name            Value
//0           32-bit integer  action          1 // announce
//4           32-bit integer  transaction_id
//8           32-bit integer  interval
//12          32-bit integer  leechers
//16          32-bit integer  seeders
//20 + 6 * n  32-bit integer  IP address
//24 + 6 * n  16-bit integer  TCP port
//20 + 6 * N
