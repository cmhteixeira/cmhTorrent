package com.cmhteixeira.bittorrent.client

import com.cmhteixeira.bittorrent.swarm.{Swarm, SwarmImpl}
import com.cmhteixeira.bittorrent.tracker.Tracker
import com.cmhteixeira.bittorrent.swarm.SwarmImpl.PeerFactory
import com.cmhteixeira.bittorrent.Torrent

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.ExecutionContext
class SwarmFactoryImpl private (
    scheduler: ScheduledExecutorService,
    peerFactoryFactory: Torrent => PeerFactory,
    mainExecutor: ExecutionContext,
    tracker: Tracker
) extends SwarmFactory {

  override def newSwarm(torrent: Torrent, blockSize: Int): Swarm =
    SwarmImpl(
      torrent = torrent,
      blockSize = blockSize,
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
      peerFactoryFactory: Torrent => PeerFactory,
      tracker: Tracker
  ): SwarmFactoryImpl = new SwarmFactoryImpl(scheduler, peerFactoryFactory, mainExecutor, tracker)
}
