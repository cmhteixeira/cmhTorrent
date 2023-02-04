package com.cmhteixeira.cmhtorrent

import com.cmhteixeira.bittorrent.PeerId
import com.cmhteixeira.bittorrent.client.{CmhClientImpl, SwarmFactoryImpl}
import com.cmhteixeira.bittorrent.peerprotocol.PeerImpl
import com.cmhteixeira.bittorrent.tracker.{RandomTransactionIdGenerator, TrackerImpl}

import java.nio.file.Paths
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory}
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global

object cmhTorrentCli extends App {
  private val peerId = PeerId("cmh-4234567891011121").getOrElse(throw new IllegalArgumentException("Peer id is bad."))
  private val cmhTorrentDir = Paths.get("/home/cmhteixeira/.cmhTorrent")

  private def scheduler(prefix: String, numThreads: Int): ScheduledExecutorService =
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

  private val peersThreadPool = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool(new ThreadFactory {
    val counter = new AtomicLong(0)

    override def newThread(r: Runnable): Thread =
      new Thread(r, s"peers-${counter.getAndIncrement()}")
  }))

  private val tracker = TrackerImpl(
    global,
    scheduler("tracker", 10),
    RandomTransactionIdGenerator(SecureRandom.getInstanceStrong),
    TrackerImpl.Config(port = 8083, peerId = peerId, key = 123)
  )

  private val swarmFactory =
    SwarmFactoryImpl(
      random = new SecureRandom(),
      scheduler = scheduler("swarm-", 4),
      mainExecutor = global,
      peerFactoryFactory = swarmTorrent =>
        inetSocketAddress =>
          PeerImpl(
            peerSocket = inetSocketAddress,
            config = PeerImpl.Config(1000, peerId),
            infoHash = swarmTorrent.infoHash,
            peersThreadPool = peersThreadPool,
            scheduledExecutorService = scheduler("Peer-", 3),
            numberOfPieces = swarmTorrent.info.pieces.size
          ),
      tracker = tracker
    )

  CmhTorrentREPL(
    CmhClientImpl(swarmFactory),
    CmhTorrentREPL.ReplConfig(cmhTorrentDir, cmhTorrentDir.resolve("history"))
  ).run()
}