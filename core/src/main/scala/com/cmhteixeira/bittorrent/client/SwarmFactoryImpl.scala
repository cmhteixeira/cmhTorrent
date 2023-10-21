package com.cmhteixeira.bittorrent.client

import com.cmhteixeira.bittorrent.swarm.{Swarm, SwarmImpl, Torrent => SwarmTorrent}
import com.cmhteixeira.bittorrent.tracker.Tracker
import com.cmhteixeira.bittorrent.swarm.SwarmImpl.PeerFactory

import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.ExecutionContext
class SwarmFactoryImpl private (
    scheduler: ScheduledExecutorService,
    peerFactoryFactory: SwarmTorrent => PeerFactory,
    mainExecutor: ExecutionContext,
    tracker: Tracker
) extends SwarmFactory {

  override def newSwarm(torrent: SwarmTorrent, downloadDir: Path, blockSize: Int): Swarm =
    SwarmImpl(
      torrent = torrent,
      blockSize = blockSize,
      downloadDir = downloadDir,
      peerFactory = peerFactoryFactory(torrent),
      scheduler = scheduler,
      mainExecutor = mainExecutor,
      tracker = tracker
    )
}

object SwarmFactoryImpl {

  def apply(
      scheduler: ScheduledExecutorService,
      mainExecutor: ExecutionContext,
      peerFactoryFactory: SwarmTorrent => PeerFactory,
      tracker: Tracker
  ): SwarmFactoryImpl = new SwarmFactoryImpl(scheduler, peerFactoryFactory, mainExecutor, tracker)
}
