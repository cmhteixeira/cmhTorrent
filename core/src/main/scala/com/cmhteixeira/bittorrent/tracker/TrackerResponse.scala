package com.cmhteixeira.bittorrent.tracker
import java.net.InetSocketAddress

sealed trait TrackerResponse
object TrackerResponse {
  case class ConnectReceived(origin: InetSocketAddress, msg: ConnectResponse, timestamp: Long) extends TrackerResponse
  case class AnnounceReceived(origin: InetSocketAddress, msg: AnnounceResponse) extends TrackerResponse
}
