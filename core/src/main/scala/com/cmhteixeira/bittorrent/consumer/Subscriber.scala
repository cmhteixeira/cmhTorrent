package com.cmhteixeira.bittorrent.consumer
import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import scodec.bits.ByteVector

import java.io.InputStream
import scala.concurrent.Future

trait Subscriber {
  def onSubscribe(s: Subscription): Unit
  def onNext(idx: Int, offset: Int, data: ByteVector): Future[Unit]
  def onComplete(): Unit
  def read(bR: BlockRequest): Future[ByteVector]
}
