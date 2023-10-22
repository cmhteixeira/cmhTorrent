package com.cmhteixeira.cmhtorrent

import com.cmhteixeira.bittorrent.PeerId
import com.cmhteixeira.bittorrent.client.{CmhClientImpl, SwarmFactoryImpl}
import com.cmhteixeira.bittorrent.peerprotocol.PeerImpl
import com.cmhteixeira.bittorrent.tracker.RandomTransactionIdGenerator
import com.cmhteixeira.bittorrent.tracker.impl.TrackerImpl
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory}
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object cmhTorrentCli extends App {
  private val peerId = PeerId("cmh-4234567891011121").getOrElse(throw new IllegalArgumentException("Peer id is bad."))
  private val cmhTorrentDir = Paths.get(System.getProperty("user.home"), ".cmhTorrent")

  private def scheduler(prefix: String, numThreads: Int): ScheduledExecutorService = {
    val counter = new AtomicLong(0)
    Executors.newScheduledThreadPool(
      numThreads,
      new ThreadFactory {
        def newThread(r: Runnable): Thread = {
          val thread = new Thread(r)
          thread.setName(s"$prefix-scheduler-${thread.getId}")
          thread.setDaemon(false)
          thread
        }
      }
    )
  }

  private def threadPool(prefix: String) = {
    val counter = new AtomicLong(0)
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool(new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val thread = new Thread(r)
        thread.setName(s"$prefix-${thread.getId}")
        thread.setDaemon(false)
        thread
      }
    }))
  }

  private val tracker = TrackerImpl(
    threadPool("trackerPool-"),
    scheduler("tracker", 0),
    RandomTransactionIdGenerator(SecureRandom.getInstanceStrong),
    TrackerImpl.Config(port = 8083, peerId = peerId, key = 123, 5 second)
  )
  private val swarmFactory =
    SwarmFactoryImpl(
      scheduler = scheduler("swarm-", 0),
      mainExecutor = global,
      peerFactoryFactory = swarmTorrent =>
        inetSocketAddress =>
          PeerImpl(
            peerSocket = inetSocketAddress,
            config = PeerImpl.Config(1000, peerId),
            infoHash = swarmTorrent.infoHash,
            peersThreadPool = threadPool("peer"),
            scheduledExecutorService = scheduler("peer", 0),
            numberOfPieces = swarmTorrent.info.pieces.size
          ),
      tracker = tracker
    )

  CmhTorrentREPL(
    CmhClientImpl(swarmFactory),
    CmhTorrentREPL.ReplConfig(cmhTorrentDir, cmhTorrentDir.resolve("history"))
  ).run()
}
