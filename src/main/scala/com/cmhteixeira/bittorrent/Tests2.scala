package com.cmhteixeira.bittorrent

import cats.syntax.show
import com.cmhteixeira.bencode
import com.cmhteixeira.bencode.Bencode.BDictionary

import java.io.FileInputStream
import scala.io.Source
import com.cmhteixeira.bencode._
import com.cmhteixeira.bittorrent.peerprotocol.{Peer, PeerImpl}
import com.cmhteixeira.bittorrent.tracker.AnnounceResponse.IPv4
import com.cmhteixeira.bittorrent.tracker.{AnnounceRequest, ConnectRequest, ConnectResponse, UdpTracker, UdpTrackerImpl}
import com.cmhteixeira.cmhtorrent.Torrent
import io.circe.Json
import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory
import sun.nio.cs.UTF_8

import java.net.{
  DatagramPacket,
  DatagramSocket,
  Inet4Address,
  InetAddress,
  InetSocketAddress,
  NetworkInterface,
  SocketAddress,
  URI,
  URL
}
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.util.Collections
import scala.collection.JavaConversions._

object Tests2 extends App {
  val logger = LoggerFactory.getLogger(getClass.getPackageName + ".Runner")
  val peerId = "cmh-1234567891011121"

  val torrentBytes = Files.readAllBytes(
    Paths
    //              .get("/home/cmhteixeira/Projects/cmhTorrent/src/test/resources/clonezillaTorrent.torrent")
    //          .get("/home/cmhteixeira/Projects/cmhTorrent/src/test/resources/Black.Adam.(2022).[720p].[WEBRip].[YTS].torrent")
    //          .get("/home/cmhteixeira/Projects/cmhTorrent/src/test/resources/15NaturecenteraugustFixed_archive.torrent")
      .get(
        "/home/cmhteixeira/Projects/cmhTorrent/src/test/resources/MagnetLinkToTorrent_99B32BCD38B9FBD8E8B40D2B693CF905D71ED97F.torrent"
      )
  )

  val bencode = parse(torrentBytes).getOrElse(throw new IllegalArgumentException("Not bencode"))

  val info = bencode.asDict.get
    .find {
      case (key, _) =>
        val res = (key.asString: Option[String]).get
        res == "info"
    }
    .get
    ._2

  val torrent =
    bencode.as[Torrent].getOrElse(throw new IllegalArgumentException("Not valid Torrent"))

  println(show.toShow(torrent).show)
  val allTrackers = torrent.announceList.getOrElse(List()).flatten.map(new URI(_))
  val url = allTrackers.lift(1).getOrElse(new URI(torrent.announce))
  println("Authority: " + url.getAuthority)
  println("Host: " + url.getHost)
  println("Path: " + url.getPath)
  println("Port: " + url.getPort)
  println("Scheme: " + url.getScheme)

  val key = 234

  val res = for {
    connected <- UdpTrackerImpl(url.getHost, url.getPort).connect(345)
    announceRequest <- AnnounceRequest(
      connected.connectionId,
      9934,
      info,
      peerId,
      0,
      0,
      0,
      AnnounceRequest.Event.Started,
      0,
      key,
      50,
      10
    ).left.map(UdpTracker.SomeRandomError)
    res <- connected.announce(announceRequest)
  } yield res

  res match {
    case Left(value) => println(value)
    case Right(value) => println(show.toShow(value).show)
  }

  val announceResponse = res.right.get

  val md: MessageDigest = MessageDigest.getInstance("SHA-1")
  val infoHash = md.digest(com.cmhteixeira.bencode.serialize(info))
  val peerIdSize = peerId.getBytes.length

  announceResponse.peers.foreach {
    case (IPv4(ip), peerPort) =>
      val farripas = PeerImpl(ip.asInstanceOf[Inet4Address], peerPort, Peer.Config(500, peerId), infoHash)
      logger.info(s"State of ${new InetSocketAddress(ip.asInstanceOf[Inet4Address], peerPort)}: " + farripas.getState)
  }

}
