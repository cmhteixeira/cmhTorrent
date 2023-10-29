package com.cmhteixeira.bittorrent.consumer

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.consumer.TorrentFileImpl.Slices
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector

import java.io.RandomAccessFile
import java.nio.file.{Files, Path}
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/**
  * NOT THREAD_SAFE.
  *
  * @param raf
  * @param tPath
  * @param slices
  */
private[swarm] final class TorrentFileImpl private (raf: RandomAccessFile, tPath: Path, private var slices: Slices)
    extends TorrentFile {

  private val logger = LoggerFactory.getLogger("TorrentFile")
  override def close(): Try[Unit] = Try(raf.close())

  override def write(pos: Long, in: ByteVector): Either[TorrentFile.WriteError, Unit] =
    slices.write(pos, in.length) match {
      case Left(TorrentFileImpl.Slices.WriteError.Overlaps(sliceOverlaps)) =>
        logger.warn(
          s"Tried to write ${in.length} bytes at offset $pos to '$tPath', but there is overlap with a finalized slice. Overlaps [${sliceOverlaps
            .map {
              case TorrentFile.Overlap(offSet, length, isFinalized) =>
                s"offset=$offSet,length:$length,finalized:$isFinalized"
            }
            .toList
            .mkString(", ")}]."
        )
        Left(TorrentFile.WriteError.AlreadyFinalized(sliceOverlaps))
      case Right(newState) =>
        (for {
          _ <- Try(raf.seek(pos))
          _ <- Try(raf.write(in.toArray))
        } yield ()) match {
          case Failure(exception) =>
            logger.warn(s"Writing ${in.length} bytes at offset $pos to '$tPath'.", exception)
            Left(TorrentFile.WriteError.Io(exception))
          // Which state should be set ?
          case Success(_) =>
            logger.debug(s"Wrote ${in.length} bytes at offset $pos to '$tPath'.")
            slices = newState // This makes the class not Thread-safe.
            Right(())
        }
    }

  override def read(pos: Long, len: Int): Either[TorrentFile.ReadError, ByteVector] =
    if (slices.size >= pos + len) {
      val bA = new Array[Byte](len)
      (for {
        _ <- Try(raf.seek(pos))
        _ <- Try(raf.readFully(bA))
      } yield ByteVector(bA)).toEither.left.map(TorrentFile.ReadError.Io)
    } else Left(TorrentFile.ReadError.OutOfBounds(slices.size))

  override def finalizeSlice(pos: Long, length: Long): Either[TorrentFile.FinalizeError, Unit] =
    slices.finalizeSlice(pos, length) match {
      case Left(Slices.FinalizeError.Overlaps(overlaps)) =>
        logger.warn(
          s"Tried to finalize slice of $length bytyes from offset $pos on '$tPath', but there is overlap with a finalized slice. Overlaps [${overlaps
            .map {
              case TorrentFile.Overlap(offSet, length, isFinalized) =>
                s"offset=$offSet,length:$length,finalized:$isFinalized"
            }
            .toList
            .mkString(", ")}]."
        )
        Left(TorrentFile.FinalizeError.AlreadyFinalized(overlaps))
      case Left(Slices.FinalizeError.OutOfBounds(len)) => Left(TorrentFile.FinalizeError.OutOfBounds(len))
      case Right(newState) =>
        logger.debug(s"Finalized slice of size $length from offset $pos for '$tPath'.")
        slices = newState // This makes the class not Thread-safe.
        Right(())
    }

  override def path: Path = tPath
}

private[swarm] object TorrentFileImpl {

  private case class Slices(i: List[(Long, SliceStatus)]) extends AnyVal {

    def size: Long = i.map(_._1).sum

    private def merge: Slices = {
      @tailrec
      def internal(
          current: (Long, SliceStatus),
          next: (Long, SliceStatus),
          remaining: List[(Long, SliceStatus)],
          acc: List[(Long, SliceStatus)]
      ): Slices =
        (current, next, remaining) match {
          case ((lSize, lStatus), (rSize, rStatus), head :: tail) if lStatus == rStatus =>
            internal(current = (lSize + rSize, lStatus), next = head, remaining = tail, acc = acc)
          case ((lSize, lStatus), (rSize, rStatus), Nil) if lStatus == rStatus =>
            Slices(acc :+ (lSize + rSize, lStatus))
          case (_, _, head :: tail) => internal(current = next, next = head, remaining = tail, acc = acc :+ current)
          case (_, _, Nil) => Slices(acc :+ current :+ next)
        }

      i match {
        case current :: next :: other => internal(current, next, other, List.empty)
        case _ => this
      }
    }

    private def overlaps(
        sizeThusFar: Long,
        offSet: Long,
        remainingSlices: List[(Long, SliceStatus)],
        acc: List[TorrentFile.Overlap]
    ): List[TorrentFile.Overlap] =
      remainingSlices match {
        case Nil => acc // todo: Might be both overlap and ousidebounds
        case (sliceLength, sliceStatus) :: other if sliceLength >= offSet =>
          acc :+ TorrentFile.Overlap(sizeThusFar, sliceLength, sliceStatus.isFinalized)
        case (sliceLength, sliceStatus) :: other =>
          overlaps(
            sizeThusFar + sliceLength,
            offSet - sliceLength,
            other,
            acc :+ TorrentFile.Overlap(sizeThusFar, sliceLength, sliceStatus.isFinalized)
          )
      }

    def write(pos: Long, length: Long): Either[Slices.WriteError, Slices] = {
      @tailrec
      def internal(
          prev: List[(Long, SliceStatus)],
          offset: Long,
          remaining: List[(Long, SliceStatus)]
      ): Either[Slices.WriteError, Slices] = {
        remaining match {
          case Nil => Right(Slices(prev :+ (offset + length) -> SliceStatus.Open)) // "grows" the file

          case (h @ (sliceSize, _)) :: tail if sliceSize <= offset => internal(prev :+ h, offset - sliceSize, tail)

          case (sliceSize, SliceStatus.Open) :: tail if sliceSize - offset >= length =>
            Right(this) // typical case, write can proceed

          case (_, SliceStatus.Open) :: Nil =>
            Right(Slices(prev :+ (offset + length -> SliceStatus.Open))) // might "grow" the file

          case (sliceSize, SliceStatus.Finalized) :: tail if sliceSize - offset >= length =>
            Left(
              Slices.WriteError.Overlaps(
                NonEmptyList.one(
                  TorrentFile.Overlap(fileOffset = prev.map(_._1).sum, sliceLength = sliceSize, finalized = true)
                )
              )
            )

          case (sliceSize, sliceStatus) :: tail =>
            val sizeSoFar = prev.map(_._1).sum
            val firstOverlap =
              TorrentFile.Overlap(
                fileOffset = sizeSoFar,
                sliceLength = sliceSize,
                finalized = sliceStatus.isFinalized
              )
            Left(
              Slices.WriteError.Overlaps(
                NonEmptyList(
                  firstOverlap,
                  overlaps(sizeSoFar + sliceSize, length - sliceSize + offset, tail, List.empty)
                )
              )
            )
        }
      }
      internal(List.empty, pos, i).map(_.merge)
    }

    def finalizeSlice(pos: Long, length: Long): Either[Slices.FinalizeError, Slices] = {
      @tailrec
      def internal(
          prev: List[(Long, SliceStatus)],
          offset: Long,
          remaining: List[(Long, SliceStatus)]
      ): Either[Slices.FinalizeError, Slices] =
        remaining match {
          case Nil => Left(Slices.FinalizeError.OutOfBounds(size.toInt))

          case (h @ (sliceSize, _)) :: tail if sliceSize <= offset => internal(prev :+ h, offset - sliceSize, tail)

          case (sliceSize, SliceStatus.Open) :: tail if sliceSize - offset >= length =>
            val newElems =
              List(
                offset -> SliceStatus.Open,
                length -> SliceStatus.Finalized,
                (sliceSize - offset - length) -> SliceStatus.Open
              ) // will require a merge after.
            Right(Slices(prev ::: newElems ::: tail))

          case (sliceSize, SliceStatus.Finalized) :: tail if sliceSize - offset >= length =>
            Left(
              Slices.FinalizeError.Overlaps(
                NonEmptyList.one(
                  TorrentFile.Overlap(fileOffset = prev.map(_._1).sum, sliceLength = sliceSize, finalized = true)
                )
              )
            )

          case (sliceSize, SliceStatus.Open) :: Nil if sliceSize - offset < length =>
            Left(Slices.FinalizeError.OutOfBounds(size.toInt))

          case (sliceSize, sliceStatus) :: tail =>
            val sizeSoFar = prev.map(_._1).sum
            val firstOverlap =
              TorrentFile.Overlap(
                fileOffset = sizeSoFar,
                sliceLength = sliceSize,
                finalized = sliceStatus.isFinalized
              )
            Left(
              Slices.FinalizeError.Overlaps(
                NonEmptyList(
                  firstOverlap,
                  overlaps(sizeSoFar + sliceSize, length - sliceSize + offset, tail, List.empty)
                )
              )
            )
        }

      internal(List.empty, pos, i).map(_.merge)
    }
  }

  private object Slices {
    sealed trait WriteError

    object WriteError {
      case class Overlaps(xs: NonEmptyList[TorrentFile.Overlap]) extends WriteError
    }

    sealed trait FinalizeError

    object FinalizeError {
      case class OutOfBounds(len: Int) extends FinalizeError
      case class Overlaps(xs: NonEmptyList[TorrentFile.Overlap]) extends FinalizeError
    }
  }

  private sealed trait SliceStatus {
    def isFinalized: Boolean
  }

  private object SliceStatus {

    case object Open extends SliceStatus {
      override def isFinalized: Boolean = false
    }

    case object Finalized extends SliceStatus {
      override def isFinalized: Boolean = true
    }
  }

  def apply(path: Path): Try[TorrentFileImpl] =
    for {
      _ <- Try(Files.createDirectories(path.getParent))
      raf <- Try(new RandomAccessFile(path.toFile, "rw"))
    } yield new TorrentFileImpl(raf, path, Slices(List.empty))
}
