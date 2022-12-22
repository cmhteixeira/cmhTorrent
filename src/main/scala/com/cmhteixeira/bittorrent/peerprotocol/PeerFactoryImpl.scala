package com.cmhteixeira.bittorrent.peerprotocol

import java.net.InetSocketAddress
import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.ExecutionContext

class PeerFactoryImpl private (
    peerConfig: PeerImpl.Config,
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService
) extends PeerFactory {

  override def peer(
      peerSocket: InetSocketAddress,
      swarmTorrent: com.cmhteixeira.bittorrent.swarm.Torrent // todo: Change to more generic type of torrent.
  ): Peer =
    PeerImpl(
      peerSocket,
      peerConfig,
      swarmTorrent.infoHash,
      mainExecutor,
      scheduler,
      swarmTorrent.info.pieces.size,
      swarmTorrent.info.pieceLength.toInt
    )
}

object PeerFactoryImpl {

  def apply(
      peerConfig: PeerImpl.Config,
      mainExecutor: ExecutionContext,
      scheduler: ScheduledExecutorService
  ) = new PeerFactoryImpl(peerConfig, mainExecutor, scheduler)
}
