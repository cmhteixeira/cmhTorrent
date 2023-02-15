package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.swarm.WriterThread.Message
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector

import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

private[swarm] class WriterThread private (
    targetDir: Path,
    @volatile var files: Map[Path, PieceFile],
    queue: BlockingQueue[Message],
    ec: ExecutionContext
) extends TorrentFileManager {
  private val logger = LoggerFactory.getLogger("BlockWriter")

  ec.execute(new Runnable { def run(): Unit = runForever() })

  private def createdNewFile(relPath: Path): Try[PieceFile] =
    PieceFileImpl(targetDir.resolve(relPath)) match {
      case error @ Failure(_) => error
      case success @ Success(pieceFile) =>
        files = files + (relPath -> pieceFile)
        success
    }

  @tailrec
  private def runForever(): Unit = {
    Try(queue.take()) match {
      case Failure(exception) =>
        logger.error("Taking element from queue.", exception)
      // todo: What to do here?
      case Success(Message(channel, file, offset, block)) =>
        files.get(file) match {
          case Some(pieceFile) => channel.tryComplete(writeBlockToFile(pieceFile, offset, block))
          case None =>
            createdNewFile(file) match {
              case Failure(exception) =>
                channel.tryFailure(new Exception(s"Creating a new torrent file for '$file'.", exception))
              case Success(pieceFile) => channel.tryComplete(writeBlockToFile(pieceFile, offset, block))
            }
        }
        runForever()
    }
  }

  private def writeBlockToFile(pieceFile: PieceFile, offSet: Int, data: ByteVector): Try[Unit] =
    for {
      _ <- pieceFile.seek(offSet)
      _ <- pieceFile.write(data.toArray)
    } yield ()

  override def write(
      file: Path,
      offset: Int,
      block: ByteVector
  ): Future[Unit] = {
    val promise = Promise[Unit]()
    queue.add(Message(promise, file, offset, block))
    logger.info(s"Queued. File: $file, offset: $offset, length: ${block.length}")
    promise.future
  }

  override def fileChunk(
      file: Path,
      offset: Int,
      chunkSize: Int
  ): Option[InputStream] = ???

}

object WriterThread {
  private case class Message(channel: Promise[Unit], file: Path, offset: Int, block: ByteVector)

  //todo: This executionContext should be specific for long running tasks
  def apply(
      downloadDir: Path,
      executionContext: ExecutionContext
  ): Try[WriterThread] =
    Success(
      new WriterThread(downloadDir, Map.empty, new LinkedBlockingQueue[Message](), executionContext)
    ) // todo: This is confusing. Improve ...
}
