package com.cmhteixeira.bittorrent.tracker

import java.net.{InetSocketAddress}

trait Tracker {
  def peers: List[Tracker.Peer]
}

object Tracker {
  case class Peer(socketAddress: InetSocketAddress) extends AnyVal
}
