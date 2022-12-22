package com.cmhteixeira.bittorrent.peerprotocol

import java.net.SocketAddress
import java.nio.file.Path
import scala.concurrent.Future

trait Peer {

  def start(): Unit
  def getState: State

  def download(pieceIndex: Int): Future[Path]

  def peerAddress: SocketAddress

  def hasPiece(index: Int): Boolean

}


