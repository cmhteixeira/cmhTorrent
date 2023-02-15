package com.cmhteixeira.bittorrent.swarm

import scodec.bits.ByteVector

import java.io.InputStream
import java.nio.file.Path
import scala.concurrent.Future

trait TorrentFileManager {
  def write(file: Path, offset: Int, block: ByteVector): Future[Unit]
  def fileChunk(file: Path, offset: Int, chunkSize: Int): Option[InputStream]
}
