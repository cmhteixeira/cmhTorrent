package com.cmhteixeira.bittorrent

import cats.Show
import cats.implicits.{catsSyntaxTuple2Semigroupal, toTraverseOps}
import com.cmhteixeira.bencode._
import com.cmhteixeira.bittorrent.tracker.{RandomTransactionIdGenerator, TrackerImpl}
import com.cmhteixeira.cmhtorrent.Torrent
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import java.nio.file.{Files, Paths}
import java.security.SecureRandom
import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.ExecutionContext.global

object Tests extends App {
  val logger = LoggerFactory.getLogger(getClass.getPackageName + ".Runner")
  val peerId = PeerId("cmh-4234567891011121").getOrElse(throw new IllegalArgumentException("Peer id is bad."))

  val dirWithTorrents = Paths.get(args(0))

  if (!Files.isDirectory(dirWithTorrents)) throw new Exception("Pass a directory.")

  val torrentFilesE =
    Files
      .list(dirWithTorrents)
      .iterator()
      .asScala
      .toList
      .map(path => Files.readAllBytes(path))
      .traverse(parse)

  val torrentFiles = torrentFilesE match {
    case Left(value) => throw new Exception(s"Something went wrong: $value")
    case Right(value) => value
  }

  val allTorrents = torrentFiles.traverse { bencode =>
    val infoComponent = (for {
      infoField <- bencode.asDict
      (_, value) <- infoField.find { case (key: Bencode, _) => (key: Bencode).asString.get == "info" }
    } yield value).toRight("Not possible to extract infohash.")

    val torrent = bencode.as[Torrent].left.map(_.toString)

    (infoComponent, torrent).mapN { case (a, b) => (a, b) }
  } match {
    case Left(value) => throw new Exception(s"There was an error: $value")
    case Right(value) => value
  }

  allTorrents.map { case (bencode, _) => InfoHash(bencode) }

  logger.info(Show[Torrent].show(allTorrents.tail.tail.head._2))

  val key = 234

  val scheduler = Executors.newSingleThreadScheduledExecutor()

  val tracker = TrackerImpl(
    global,
    scheduler,
    RandomTransactionIdGenerator(SecureRandom.getInstanceStrong),
    TrackerImpl.Config(8083, peerId, 123)
  )

  logger.info("Tracker created ...")

  val all = allTorrents.traverse {
    case (bencode, thisTorrent) =>
      com.cmhteixeira.bittorrent.tracker
        .Torrent(InfoHash(bencode), thisTorrent)
        .map(trackerTorrent => (bencode, thisTorrent, trackerTorrent))
  } match {
    case Left(value) => throw new Exception(s"Some error: $value")
    case Right(value) => value
  }
  logger.info("Foo Bar ...")
  all.foreach {
    case (_, _, c) =>
      tracker.submit(c)
  }

  scheduler.scheduleAtFixedRate(
    new Runnable {

      def run(): Unit =
        all.foreach {
          case (a, b, c) =>
            val peers = tracker.peers(c.infoHash)
            logger.info(s"There are ${peers.size} distinct peers for '${c.infoHash}': [${peers.mkString("")}]")
        }
    },
    15,
    15,
    TimeUnit.SECONDS
  )

}