package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.peerprotocol.{Peer, PeerImpl}

import java.net.InetSocketAddress
import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.ExecutionContext

final class PeerFactoryImpl private (
    peerConfig: PeerImpl.Config,
    torrent: Torrent,
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService
) extends PeerFactory {

  override def peer(
      peerSocket: InetSocketAddress
  ): Peer =
    PeerImpl(
      peerSocket,
      peerConfig,
      torrent.infoHash,
      mainExecutor,
      scheduler,
      torrent.info.pieces.size
    )
}

object PeerFactoryImpl {

  def apply(
      peerConfig: PeerImpl.Config,
      torrent: Torrent,
      mainExecutor: ExecutionContext,
      scheduler: ScheduledExecutorService
  ) = new PeerFactoryImpl(peerConfig, torrent, mainExecutor, scheduler)
}
