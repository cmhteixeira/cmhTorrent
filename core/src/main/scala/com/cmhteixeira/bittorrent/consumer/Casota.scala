package com.cmhteixeira.bittorrent.consumer

trait Casota {
  def cancel(): Unit
  def request(i: Int): Unit
  def completed(pieceIdx: Int): Unit /* ???? */
}
