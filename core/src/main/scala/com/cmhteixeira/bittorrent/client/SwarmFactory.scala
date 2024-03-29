package com.cmhteixeira.bittorrent.client

import com.cmhteixeira.bittorrent.swarm.{Swarm, Torrent => SwarmTorrent}

import java.nio.file.Path

trait SwarmFactory {
  def newSwarm(torrent: SwarmTorrent, downloadDir: Path, blockSize: Int): Swarm
}
