package com.cmhteixeira.bittorrent.client

import com.cmhteixeira.bittorrent.swarm.Swarm
import com.cmhteixeira.bittorrent.Torrent

trait SwarmFactory {
  def newSwarm(torrent: Torrent, blockSize: Int): Swarm
}
