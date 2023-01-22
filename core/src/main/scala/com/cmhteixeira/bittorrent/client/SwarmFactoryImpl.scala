package com.cmhteixeira.bittorrent.client

import com.cmhteixeira.bittorrent.swarm.{PeerFactory, PeerFactoryImpl, SwarmImpl, Torrent => SwarmTorrent}
import com.cmhteixeira.bittorrent.tracker.Tracker
import com.cmhteixeira.bittorrent.peerprotocol.PeerImpl
import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.ExecutionContext
import scala.util.Random

class SwarmFactoryImpl private (
    random: Random,
    peerConfig: PeerImpl.Config,
    scheduler: ScheduledExecutorService,
    mainExecutor: ExecutionContext,
    tracker: Tracker
) extends SwarmFactory {

  override def newSwarm(torrent: SwarmTorrent, downloadDir: Path, blockSize: Int): SwarmImpl =
    SwarmImpl(
      torrent = torrent,
      blockSize = blockSize,
      downloadDir = downloadDir,
      random = random,
      peerFactory = PeerFactoryImpl(peerConfig, torrent, mainExecutor, scheduler), // todo: change this
      scheduler = scheduler,
      mainExecutor = mainExecutor,
      tracker = tracker
    )
}

object SwarmFactoryImpl {

  def apply(
      random: Random,
      peerConfig: PeerImpl.Config,
      scheduler: ScheduledExecutorService,
      mainExecutor: ExecutionContext,
      tracker: Tracker
  ): SwarmFactoryImpl = new SwarmFactoryImpl(random, peerConfig, scheduler, mainExecutor, tracker)
}
