package com.cmhteixeira.bittorrent.swarm

import java.io.RandomAccessFile
import java.nio.file.Path
import scala.util.Try

private[swarm] class PieceFileImpl private (raf: RandomAccessFile, thePath: Path) extends PieceFile {
  override def close(): Try[Unit] = Try(raf.close())
  override def seek(pos: Long): Try[Unit] = Try(raf.seek(pos))
  override def write(in: Array[Byte]): Try[Unit] = Try(raf.write(in))
  override def path: Path = thePath

}

private[swarm] object PieceFileImpl {

  def apply(path: Path): PieceFileImpl = new PieceFileImpl(new RandomAccessFile(path.toFile, "rw"), path)
}
