package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.swarm.WriterThread.Message
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector

import java.util.concurrent.BlockingQueue
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Failure, Success, Try}

class WriterThread private (queue: BlockingQueue[WriterThread.Message], ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger("BlockWriter")

  ec.execute(new Runnable { def run(): Unit = runForever() })

  def add(msg: Message): Unit = {
    queue.add(msg)
    val Message(_, offset, pieceFile, block) = msg
    logger.info(s"Queued message. Path: ${pieceFile.path}, offset: $offset, length: ${block.length}")
  }

  @tailrec
  private def runForever(): Unit = {
    Try(queue.take()) match {
      case Failure(exception) => logger.error("Taking element from queue.", exception)
      case Success(msg @ Message(channel, offset, pieceFile, block)) =>
        writeBlockToFile(msg) match {
          case Failure(exception) =>
            val msg =
              s"Writing block to '${pieceFile.path}'. Offset: $offset, length: ${block.length}. Queue size: ${queue.size()}."
            logger.error(msg, exception)
            channel.failure(new Exception(msg, exception))
          case Success(_) =>
            logger.debug(
              s"Wrote block to '${pieceFile.path}'. Offset: $offset, length: ${block.length}. Queue size: ${queue.size()}."
            )
            channel.success(())
            runForever()
        }
    }
  }

  private def writeBlockToFile(msg: Message): Try[Unit] = {
    val Message(_, offset, file, block) = msg
    for {
      _ <- file.seek(offset)
      _ <- file.write(block.toArray)
    } yield ()
  }
}

object WriterThread {
  case class Message(channel: Promise[Unit], offset: Int, file: PieceFile, block: ByteVector)

  def apply(executionContext: ExecutionContext, blockingQueue: BlockingQueue[WriterThread.Message]): WriterThread =
    new WriterThread(blockingQueue, executionContext) // todo: This is confusing. Improve ...
}
