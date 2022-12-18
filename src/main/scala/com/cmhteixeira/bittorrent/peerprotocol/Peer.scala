package com.cmhteixeira.bittorrent.peerprotocol

import java.io.File
import java.net.SocketAddress
import java.nio.file.Path
import scala.concurrent.{Future, Promise}

trait Peer {
  def getState: State

  def download(pieceHash: String): Future[Path]

  def peerAddress: SocketAddress
}

object Peer {

  case class Config(tcpConnectTimeoutMillis: Int, myPeerId: String)
}
