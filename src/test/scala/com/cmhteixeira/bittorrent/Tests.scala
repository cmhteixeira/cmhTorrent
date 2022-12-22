package com.cmhteixeira.bittorrent

import cats.Show
import cats.implicits.{catsSyntaxTuple2Semigroupal, toTraverseOps}
import com.cmhteixeira.bencode._
import com.cmhteixeira.bittorrent.peerprotocol.{PeerFactoryImpl, PeerImpl}
import com.cmhteixeira.bittorrent.swarm.SwarmImpl
import com.cmhteixeira.bittorrent.tracker.{RandomTransactionIdGenerator, TrackerImpl}
import com.cmhteixeira.cmhtorrent.{MultiFile, SingleFile, Torrent}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import java.nio.file.{Files, Paths}
import java.security.SecureRandom
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import scala.concurrent.ExecutionContext.global

object Tests extends App {

  val logger = LoggerFactory.getLogger("Runner")
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

  logger.info(Show[Torrent].show(allTorrents.tail.head._2))

  val key = 234

  def scheduler(prefix: String, numThreads: Int): ScheduledExecutorService =
    Executors.newScheduledThreadPool(
      numThreads,
      new ThreadFactory {
        val counter = new AtomicLong(0)

        def newThread(r: Runnable): Thread = {
          val thread = new Thread(r, s"$prefix-${counter.getAndIncrement()}")
          thread.setDaemon(false)
          thread
        }
      }
    )

  val tracker = TrackerImpl(
    global,
    scheduler("tracker", 10),
    RandomTransactionIdGenerator(SecureRandom.getInstanceStrong),
    TrackerImpl.Config(8083, peerId, 123)
  )

  logger.info("Tracker created ...")

  val all = allTorrents.slice(1, 2).traverse {
    case (bencode, thisTorrent) =>
      com.cmhteixeira.bittorrent.swarm
        .Torrent(InfoHash(bencode), thisTorrent)
        .map(swarmTorrent => (bencode, thisTorrent, swarmTorrent))
  } match {
    case Left(value) => throw new Exception(s"Some error: $value")
    case Right(value) => value
  }

  val (_, torrent, swarmTorrent) = all.head
  val downloadDir = Paths.get("/home/cmhteixeira/Projects/cmhTorrent/src/test/scala/com/cmhteixeira")

  val peerFactory = PeerFactoryImpl(PeerImpl.Config(1000, peerId, downloadDir), global, scheduler("peers", 10))

  val swarm =
    SwarmImpl(tracker, global, scheduler("swarm", 10), peerFactory, swarmTorrent)

}
