package com.cmhteixeira.bittorrent.client.history
import java.nio.file.Path

case class PathAndPieces(path: Path, pieces: Set[Int])
