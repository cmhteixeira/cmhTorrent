package com.cmhteixeira.bittorrent.client

import com.cmhteixeira.bittorrent.swarm.{SwarmImpl, Torrent => SwarmTorrent}
import com.cmhteixeira.bittorrent.tracker.Tracker
import com.cmhteixeira.bittorrent.swarm.SwarmImpl.PeerFactory

import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.ExecutionContext
import scala.util.Random

class SwarmFactoryImpl private (
    random: Random,
    scheduler: ScheduledExecutorService,
    peerFactoryFactory: SwarmTorrent => PeerFactory,
    mainExecutor: ExecutionContext,
    tracker: Tracker
) extends SwarmFactory {

  override def newSwarm(torrent: SwarmTorrent, downloadDir: Path, blockSize: Int): SwarmImpl =
    SwarmImpl(
      torrent = torrent,
      blockSize = blockSize,
      downloadDir = downloadDir,
      random = random,
      peerFactory = peerFactoryFactory(torrent),
      scheduler = scheduler,
      mainExecutor = mainExecutor,
      tracker = tracker
    )
}

object SwarmFactoryImpl {

  def apply(
      random: Random,
      scheduler: ScheduledExecutorService,
      mainExecutor: ExecutionContext,
      peerFactoryFactory: SwarmTorrent => PeerFactory,
      tracker: Tracker
  ): SwarmFactoryImpl = new SwarmFactoryImpl(random, scheduler, peerFactoryFactory, mainExecutor, tracker)
}
