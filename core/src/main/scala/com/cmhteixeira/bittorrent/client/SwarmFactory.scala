package com.cmhteixeira.bittorrent.client

import com.cmhteixeira.bittorrent.swarm.SwarmImpl
import com.cmhteixeira.bittorrent.swarm.{Torrent => SwarmTorrent}

import java.nio.file.Path

trait SwarmFactory {
  def newSwarm(torrent: SwarmTorrent, downloadDir: Path, blockSize: Int): SwarmImpl
}
