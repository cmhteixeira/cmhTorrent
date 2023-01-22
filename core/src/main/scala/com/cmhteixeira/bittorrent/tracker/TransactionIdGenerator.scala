package com.cmhteixeira.bittorrent.tracker

trait TransactionIdGenerator {
  def newTransactionId(): Int
}
