package com.cmhteixeira.bittorrent

import cats.Show
import com.cmhteixeira.bencode._
import com.cmhteixeira.bittorrent.tracker.{RandomTransactionIdGenerator, Tracker, TrackerImpl}
import com.cmhteixeira.cmhtorrent.Torrent
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Paths}
import java.security.SecureRandom
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext.global

object Tests extends App {
  val logger = LoggerFactory.getLogger(getClass.getPackageName + ".Runner")
  val peerId = PeerId("cmh-4234567891011121").getOrElse(throw new IllegalArgumentException("Peer id is bad."))

  val torrentBytes = Files.readAllBytes(
    Paths
    //      .get("/home/cmhteixeira/Projects/cmhTorrent/src/test/resources/clonezillaTorrent.torrent")
      .get("/home/cmhteixeira/Projects/cmhTorrent/src/test/resources/Black.Adam.(2022).[720p].[WEBRip].[YTS].torrent")
//      .get("/home/cmhteixeira/Projects/cmhTorrent/src/test/resources/15NaturecenteraugustFixed_archive.torrent")
//      .get(
//        "/home/cmhteixeira/Projects/cmhTorrent/src/test/resources/MagnetLinkToTorrent_99B32BCD38B9FBD8E8B40D2B693CF905D71ED97F.torrent"
//      )
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

  val key = 234

  val scheduler = Executors.newSingleThreadScheduledExecutor()

  val tracker = TrackerImpl(
    global,
    scheduler,
    RandomTransactionIdGenerator(SecureRandom.getInstanceStrong),
    TrackerImpl.Config(8083, peerId, 123)
  )

  val torrent2 = com.cmhteixeira.bittorrent.tracker
    .Torrent(InfoHash(info), torrent)
    .fold(
      error => throw new IllegalArgumentException(s"Error: $error.${Show[Torrent].show(torrent)}"),
      a => a
    )

  tracker.submit(Tracker.Torrent(InfoHash(info), torrent2.announce, torrent2.announceList))

  Thread.sleep(100000)
}
