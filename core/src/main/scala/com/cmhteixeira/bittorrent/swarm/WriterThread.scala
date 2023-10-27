package com.cmhteixeira.bittorrent.swarm

import cats.implicits.toTraverseOps
import com.cmhteixeira.bittorrent.swarm.WriterThread.{FinalizeSlice, Message, Read, Write}
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

private[swarm] class WriterThread private (
    targetDir: Path,
    @volatile var files: Map[Path, TorrentFile],
    queue: BlockingQueue[Message],
    ec: ExecutionContext
) extends TorrentFileManager {
  private val logger = LoggerFactory.getLogger("BlockWriter")

  ec.execute(new Runnable { def run(): Unit = runForever() })

  @tailrec
  private def runForever(): Unit =
    Try(queue.take()) match {
      case Failure(exception) =>
        logger.error("Taking element from queue.", exception)
      // todo: What to do here?
      case Success(write: Write) =>
        dealWithWrite(write)
        runForever()

      case Success(read: Read) =>
        dealWithRead(read)
        runForever()

      case Success(finalize: FinalizeSlice) =>
        dealWithFinalize(finalize)
        runForever()
    }

  private def dealWithRead(msg: Read): Unit = {
    val Read(promise, path, offset, len) = msg
    files.get(path) match {
      case Some(tF) =>
        tF.read(offset, len.toInt /* todo fix */ ) match {
          case Left(TorrentFile.ReadError.Io(throwable)) =>
            promise.tryFailure(new IOException(s"Reading $len from offset $offset of '$path'.", throwable))

          case Left(TorrentFile.ReadError.OutOfBounds(currentLen)) =>
            promise.tryFailure(
              new IndexOutOfBoundsException(s"Reading $len from offset $offset of '$path' but length is $currentLen.")
            )
          case Right(byteVector) => promise.trySuccess(byteVector)
        }
      case None => promise.tryFailure(new Exception(s"Unknown path: $path"))
    }
  }

  private def dealWithFinalize(msg: FinalizeSlice): Unit = {
    val FinalizeSlice(promise, slices, cond) = msg
    slices
      .traverse {
        case (path, off, len) =>
          files.get(path) match {
            case Some(tF) => Right((tF, off, len))
            case None => Left((path, off, len))
          }
      } match {
      case Left((path, offset, len)) =>
        promise.tryFailure(
          new IllegalArgumentException(
            s"Couldn't finalize slice offset: $offset, len: $len because file '$path' does not exist. Files: [${files.keys.map(_.toString).mkString(", ")}]."
          )
        )
      case Right(slices) =>
        val readSlices = slices.map { case tuple @ (tF, off, len) => tuple -> tF.read(off, len.toInt) }
        val success = readSlices.collect { case (tuple, Right(value)) => tuple -> value }
        val failure = readSlices.collect { case (tuple, Left(value)) => tuple -> value }

        if (failure.nonEmpty)
          promise.tryFailure(
            new IllegalStateException(
              s"While trying to reading slices in order to verify hash, there were errors reading one or more: [${failure
                .map {
                  case ((tF, offset, len), TorrentFile.ReadError.Io(_)) =>
                    s"Slice[file=${tF.path},offset=$offset,len:$len]:IO"
                  case ((tF, offset, len), TorrentFile.ReadError.OutOfBounds(fileSize)) =>
                    s"Slice[file=${tF.path},offset=$offset,len:$len]:OutOfBounds(fileSize=$fileSize)"
                }
                .mkString(", ")}]"
            )
          )
        else {
          if (cond(ByteVector.concat(success.map(_._2))))
            success.map(_._1).traverse { case (tf, off, len) => tf.finalizeSlice(off, len) } match {
              case Left(TorrentFile.FinalizeError.Io(error)) =>
                promise.tryFailure(
                  new IOException(
                    s"Finalizing slices [${slices.map { case (a, b, c) => s"${a.path},offset=$b,len=$c" }}]",
                    error
                  )
                )

              case Left(TorrentFile.FinalizeError.OutOfBounds(lengthFile)) =>
                promise.tryFailure(
                  new IndexOutOfBoundsException(
                    s"Finalizing slices [${slices.map { case (a, b, c) => s"${a.path},offset=$b,len=$c" }}]. Got index out of bounds for a slice. Actual length: $lengthFile"
                  )
                )

              case Left(TorrentFile.FinalizeError.AlreadyFinalized(overlaps)) =>
                promise.tryFailure(
                  new IllegalStateException(
                    s"Finalizing slices [${slices.map { case (a, b, c) => s"${a.path},offset=$b,len=$c" }}]. But at least one slice already finalized."
                  )
                )
              case Right(_) => promise.trySuccess(true)
            }
          else promise.trySuccess(false)
        }
    }
  }

  private def dealWithWrite(msg: Write): Unit = {
    val Write(channel, file, offset, block) = msg
    files.get(file) match {
      case Some(pieceFile) =>
        channel.tryComplete(writeBlockToFile(pieceFile, offset, block))
      case None =>
        createdNewFile(file) match {
          case Failure(exception) =>
            channel.tryFailure(new Exception(s"Creating a new torrent file for '$file'.", exception))
          case Success(pieceFile) => channel.tryComplete(writeBlockToFile(pieceFile, offset, block))
        }
    }
  }

  private def writeBlockToFile(pieceFile: TorrentFile, offSet: Long, data: ByteVector): Try[Unit] =
    pieceFile.write(offSet, data) match {
      case Left(TorrentFile.WriteError.Io(exception)) =>
        Failure(new IOException(s"Writing ${data.length} from offset $offSet to '${pieceFile.path}'.", exception))
      case Left(TorrentFile.WriteError.AlreadyFinalized(overlap)) =>
        Failure(
          new IllegalArgumentException(
            s"Writing ${data.length} from offset $offSet to '${pieceFile.path}', but slice already finished."
          )
        )
      case Right(_) => Success(())
    }

  private def createdNewFile(relPath: Path): Try[TorrentFile] =
    TorrentFileImpl(targetDir.resolve(relPath)) match {
      case error @ Failure(_) => error
      case success @ Success(pieceFile) =>
        files = files + (relPath -> pieceFile)
        success
    }

  override def write(
      file: Path,
      offset: Long,
      block: ByteVector
  ): Future[Unit] = {
    val promise = Promise[Unit]()
    queue.add(Write(promise, file, offset, block))
    logger.info(s"Queued write. File: $file, offset: $offset, length: ${block.length}. Queue size: ${queue.size()}")
    promise.future
  }

  override def read(
      file: Path,
      offset: Long,
      chunkSize: Int
  ): Future[ByteVector] = {
    val promise = Promise[ByteVector]()
    queue.add(Read(promise, file, offset, chunkSize))
    logger.info(s"Queued read. File: $file, offset: $offset, length: $chunkSize. Queue size: ${queue.size()}")
    promise.future
  }

  override def complete(slices: List[TorrentFileManager.FileSlice])(
      cond: ByteVector => Boolean
  ): Future[Boolean] = {
    val promise = Promise[Boolean]()
    queue.add(
      FinalizeSlice(
        promise,
        slices.map { case TorrentFileManager.FileSlice(path, offset, len) => (path, offset.toLong, len.toLong) },
        cond
      )
    )
    logger.info(
      s"Queued slice completes: [${slices.map(fS => s"${fS.path},offset:${fS.offset},len:${fS.size}").mkString(", ")}]. Queue size: ${queue.size()}"
    )
    promise.future
  }
}

object WriterThread {

  private sealed trait Message
  private case class Write(channel: Promise[Unit], file: Path, offset: Long, block: ByteVector) extends Message

  private case class Read(channel: Promise[ByteVector], file: Path, offset: Long, len: Long) extends Message

  private case class FinalizeSlice(
      channel: Promise[Boolean],
      slices: List[(Path, Long, Long)],
      cond: ByteVector => Boolean
  ) extends Message

  //todo: This executionContext should be specific for long running tasks
  def apply(
      downloadDir: Path,
      executionContext: ExecutionContext
  ): Try[WriterThread] =
    Success(
      new WriterThread(downloadDir, Map.empty, new LinkedBlockingQueue[Message](), executionContext)
    ) // todo: This is confusing. Improve ...
}
