package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.cmhtorrent.Torrent

private[bittorrent] class SwarmImpl private (torrent: Torrent) extends Swarm {}

object SwarmImpl {
  def apply(): SwarmImpl = new SwarmImpl(???)
}
