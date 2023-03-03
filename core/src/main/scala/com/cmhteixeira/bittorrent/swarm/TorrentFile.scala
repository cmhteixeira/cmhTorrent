package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import scodec.bits.ByteVector

import java.nio.file.Path
import scala.util.Try

trait TorrentFile {
  def close(): Try[Unit]
  def write(pos: Long, in: ByteVector): Either[TorrentFile.WriteError, Unit]
  def read(pos: Long, len: Int): Either[TorrentFile.ReadError, ByteVector]
  def finalizeSlice(pos: Long, length: Long): Either[TorrentFile.FinalizeError, Unit]
  def path: Path
}

object TorrentFile {
  case class Overlap(fileOffset: Long, sliceLength: Long, finalized: Boolean)
  sealed trait WriteError

  object WriteError {
    case class Io(error: Throwable) extends WriteError
    case class AlreadyFinalized(overlaps: NonEmptyList[Overlap]) extends WriteError
  }

  sealed trait ReadError

  object ReadError {
    case class Io(error: Throwable) extends ReadError
    case class OutOfBounds(len: Long) extends ReadError
  }

  sealed trait FinalizeError

  object FinalizeError {
    case class Io(error: Throwable) extends FinalizeError
    case class OutOfBounds(len: Long) extends FinalizeError
    case class AlreadyFinalized(overlaps: NonEmptyList[Overlap]) extends FinalizeError
  }

}
