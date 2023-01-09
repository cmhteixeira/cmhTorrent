package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.peerprotocol.Peer

import java.net.InetSocketAddress

trait PeerFactory {

  def peer(
      peerSocket: InetSocketAddress
  ): Peer
}
