package com.cmhteixeira.bittorrent.tracker

trait UdpConnected {
  def connectionId: Long
  def announce(request: AnnounceRequest): Either[UdpTracker.Error, AnnounceResponse]
}
