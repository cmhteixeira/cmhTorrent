package com.cmhteixeira.bittorrent.tracker

trait TransactionIdGenerator {
  def txnId(): Int
}
