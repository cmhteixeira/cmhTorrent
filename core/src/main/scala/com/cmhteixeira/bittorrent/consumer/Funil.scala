package com.cmhteixeira.bittorrent.consumer
import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest

import java.io.InputStream
import scala.concurrent.Future

trait Funil {
  def onSubscribe(s: Casota): Unit
  def onNext(bR: BlockRequest): Future[Unit]
  def onComplete(): Unit
  def read(bR: BlockRequest): InputStream
}
