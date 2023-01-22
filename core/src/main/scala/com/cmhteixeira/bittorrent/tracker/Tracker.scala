package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent.InfoHash
import java.net.InetSocketAddress

trait Tracker {
  def peers(infoHash: InfoHash): List[InetSocketAddress]

  def submit(torrent: Torrent): Unit
}