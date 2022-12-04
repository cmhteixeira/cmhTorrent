package com.cmhteixeira.bittorrent.peerprotocol

import java.io.Serializable
import java.net.SocketAddress

trait Peer {
  def getState: State

  def peerAddress: SocketAddress
}

object Peer {

  case class Config(tcpConnectTimeoutMillis: Int, myPeerId: String)
}
