package com.cmhteixeira.bittorrent.swarm

import java.nio.file.Path
import scala.util.Try

trait PieceFile {
  def close(): Try[Unit]
  def seek(pos: Long): Try[Unit]
  def write(in: Array[Byte]): Try[Unit]
  def path: Path
}
