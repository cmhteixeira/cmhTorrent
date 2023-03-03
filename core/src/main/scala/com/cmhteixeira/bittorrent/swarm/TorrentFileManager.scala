package com.cmhteixeira.bittorrent.swarm

import scodec.bits.ByteVector

import java.nio.file.Path
import scala.concurrent.Future

trait TorrentFileManager {
  def write(file: Path, offset: Int, block: ByteVector): Future[Unit]
  def read(file: Path, offset: Int, chunkSize: Int): Future[ByteVector]

  def complete(slices: List[TorrentFileManager.FileSlice])(cond: ByteVector => Boolean): Future[Boolean]
}

object TorrentFileManager {
  case class FileSlice(path: Path, offset: Int, size: Int)
}
