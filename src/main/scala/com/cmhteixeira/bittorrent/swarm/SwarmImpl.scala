package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.swarm.State.{PeerState, Pieces}
import com.cmhteixeira.bittorrent.tracker.Tracker

import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{LinkedBlockingQueue, ScheduledExecutorService, TimeUnit}
import scala.concurrent.ExecutionContext
import scala.util.Random

private[bittorrent] class SwarmImpl private (
    torrent: Torrent,
    tracker: Tracker,
    scheduler: ScheduledExecutorService,
    upsertPeers: UpsertPeers,
    requestBlocks: RequestBlocks
) {
  tracker.submit(torrent.toTrackerTorrent)
  scheduler.scheduleAtFixedRate(upsertPeers, 0, 60, TimeUnit.SECONDS)
  scheduler.scheduleAtFixedRate(requestBlocks, 0, 20, TimeUnit.SECONDS)
}

object SwarmImpl {

  def apply(
      tracker: Tracker,
      mainExecutor: ExecutionContext,
      scheduler: ScheduledExecutorService,
      peerFactory: PeerFactory,
      random: Random,
      downloadDir: Path,
      blockSize: Int,
      torrent: Torrent
  ): SwarmImpl = {
    val peers = new AtomicReference[Map[InetSocketAddress, PeerState]](Map.empty)
    val pieces = new AtomicReference[State.Pieces](Pieces.from(torrent.info.pieces))
    val writerThread = WriterThread(mainExecutor, new LinkedBlockingQueue[WriterThread.Message]())

    new SwarmImpl(
      torrent,
      tracker,
      scheduler,
      UpsertPeers(peers, peerFactory, torrent, tracker),
      RequestBlocks(
        peers,
        pieces,
        writerThread,
        torrent,
        random,
        RequestBlocks.Configuration(downloadDir, blockSize),
        mainExecutor,
        scheduler
      )
    )
  }
}
