package com.cmhteixeira.bittorrent.peerprotocol

import java.net.SocketAddress
import java.nio.file.Path
import scala.concurrent.Future

trait Peer {
  def getState: State

  def download(pieceIndex: Int): Future[Path]

  def peerAddress: SocketAddress
}

object Peer
