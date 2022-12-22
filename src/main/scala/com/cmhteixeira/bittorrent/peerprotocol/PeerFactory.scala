package com.cmhteixeira.bittorrent.peerprotocol

import java.net.InetSocketAddress

trait PeerFactory {

  def peer(
      peerSocket: InetSocketAddress,
      swarmTorrent: com.cmhteixeira.bittorrent.swarm.Torrent // todo: Change to more generic type of torrent.
  ): Peer
}
