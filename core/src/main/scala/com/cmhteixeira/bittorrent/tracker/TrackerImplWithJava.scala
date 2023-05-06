package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent
import com.cmhteixeira.bittorrent2.tracker.TrackerJavaImpl

import java.net.InetSocketAddress

class TrackerImplWithJava private (trackerJavaImpl: TrackerJavaImpl) extends Tracker {
  override def peers(
      infoHash: bittorrent.InfoHash
  ): Set[InetSocketAddress] = ???
  override def statistics: Map[bittorrent.InfoHash, Tracker.Statistics] =
    ???
  override def submit(torrent: Torrent): Unit = ???
}

object TrackerImplWithJava {
  def apply(trackerJavaImpl: TrackerJavaImpl): TrackerImplWithJava = new TrackerImplWithJava(trackerJavaImpl)
}
