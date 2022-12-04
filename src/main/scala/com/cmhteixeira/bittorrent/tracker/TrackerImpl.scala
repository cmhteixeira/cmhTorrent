package com.cmhteixeira.bittorrent.tracker

private[tracker] class TrackerImpl private () extends Tracker {

  override def peers: List[Tracker.Peer] =
    ???
}

object TrackerImpl {
  def apply(): TrackerImpl = new TrackerImpl
}
