package com.cmhteixeira.bittorrent.client.history
import com.cmhteixeira.bittorrent.InfoHash

import java.nio.file.Path

class Monkey private (file: Path) {
  lazy val history: History = ???
  def pieceCompleted(torrent: InfoHash, idx: Int): Unit = ???
}

object Monkey {}
