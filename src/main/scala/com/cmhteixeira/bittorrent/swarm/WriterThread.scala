package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.swarm.WriterThread.Message
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector
import java.io.RandomAccessFile
import java.util.concurrent.BlockingQueue
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

class WriterThread private (blockingQueue: BlockingQueue[WriterThread.Message], ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger("Swarm-FileWriter")

  ec.execute(new Runnable { def run(): Unit = runForever() })

  def add(msg: Message): Unit = {
    val Message(index, offset, _, block) = msg
    logger.info(s"Submitting message. Piece: $index, offset: $offset, length: ${block.length}")
    blockingQueue.add(msg)
  }

  private def runForever(): Unit = {
    Try(blockingQueue.take()) match {
      case Failure(exception) => logger.error("Taking element from queue.", exception)
      case Success(msg @ Message(index, offset, _, block)) =>
        writeBlockToFile(msg) match {
          case Failure(exception) =>
            logger.error(s"Writing block. Piece: $index, offset: $offset, length: ${block.length}.", exception)
          case Success(_) =>
            logger.info(
              s"Wrote block. Piece: $index, offset: $offset, length: ${block.length}. There are ${blockingQueue.size()} in the queue."
            )
            runForever()
        }
    }
  }

  private def writeBlockToFile(msg: Message): Try[Unit] = {
    val Message(_, offset, file, block) = msg
    for {
      _ <- Try(file.seek(offset))
      _ <- Try(file.write(block.toArray))
    } yield ()

    // check if it is the last block of this piece.
    //    - if so, update state and finish promise channel
  }

}

object WriterThread {
  case class Message(pieceIndex: Int, offset: Int, file: RandomAccessFile, block: ByteVector)

  def apply(executionContext: ExecutionContext, blockingQueue: BlockingQueue[WriterThread.Message]): WriterThread =
    new WriterThread(blockingQueue, executionContext) // todo: This is confusing. Improve ...
}
