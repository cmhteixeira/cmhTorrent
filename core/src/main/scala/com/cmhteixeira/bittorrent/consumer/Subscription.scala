package com.cmhteixeira.bittorrent.consumer

trait Subscription {
  def cancel(): Unit
  def request(i: Int): Unit
  def completed(pieceIdx: Int): Unit /* ???? */
  def wrongHash(pieceIdx: Int): Unit
}
