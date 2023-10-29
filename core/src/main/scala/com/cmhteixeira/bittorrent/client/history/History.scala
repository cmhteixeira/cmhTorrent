package com.cmhteixeira.bittorrent.client.history
import com.cmhteixeira.bittorrent.InfoHash
import io.circe.Decoder

case class History(pathAndPieces: Map[InfoHash, PathAndPieces])

object History {
  val decoder: Decoder[History] = ???
}
