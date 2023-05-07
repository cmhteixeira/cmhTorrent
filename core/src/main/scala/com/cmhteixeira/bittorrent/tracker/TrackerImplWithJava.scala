package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent
import com.cmhteixeira.bittorrent2.tracker.TrackerJavaImpl

import java.net.InetSocketAddress
import java.util.Collections

class TrackerImplWithJava private (trackerJavaImpl: TrackerJavaImpl) extends Tracker {
  override def peers(
      infoHash: bittorrent.InfoHash
  ): Set[InetSocketAddress] = ???
  override def statistics: Map[bittorrent.InfoHash, Tracker.Statistics] =
    ???
  override def submit(torrent: Torrent): Unit =
    trackerJavaImpl.submit(torrent.infoHash.hex, Collections.emptyList())
}

object TrackerImplWithJava {
  def apply(trackerJavaImpl: TrackerJavaImpl): TrackerImplWithJava = new TrackerImplWithJava(trackerJavaImpl)

  def apply(port: Int): TrackerImplWithJava = new TrackerImplWithJava(new TrackerJavaImpl(port))
}
