package com.cmhteixeira.bittorrent.consumer

import cats.implicits.toTraverseOps
import com.cmhteixeira.bittorrent.consumer.FileSystemSink.State.Active
import com.cmhteixeira.bittorrent.Torrent
import com.cmhteixeira.bittorrent.Torrent.{File, FileChunk, FileSlice}
import com.cmhteixeira.bittorrent.consumer.FileSystemSink.{Message, State}
import com.cmhteixeira.bittorrent.peerprotocol.Peer
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

class FileSystemSink private (
    queue: BlockingQueue[FileSystemSink.Message],
    state: AtomicReference[FileSystemSink.State]
)(implicit val executionContext: ExecutionContext) {
  private val logger = LoggerFactory.getLogger("FileSystemSink")

  private class Writer extends Thread {
    override def run(): Unit =
      Try(queue.take()) match {
        case Failure(exception) => logger.error("Retrieving an element from the queue. Exiting thread.", exception)
        case Success(read: Message.Read) => dealWithRead(read)
        case Success(write: Message.Write) => dealWithWrite(write)
      }

    private def dealWithRead(msg: Message.Read): Unit = {
      val Message.Read(promise, path, offset, len) = msg
      val currentState = state.get()
      currentState match {
        case State.Inactive => promise.tryFailure(new IllegalStateException("TODO"))
        case Active(_, _, files, _) =>
          files.get(path) match {
            case Some(tF) =>
              tF.read(offset, len.toInt /* todo fix */ ) match {
                case Left(TorrentFile.ReadError.Io(throwable)) =>
                  promise.tryFailure(new IOException(s"Reading $len from offset $offset of '$path'.", throwable))

                case Left(TorrentFile.ReadError.OutOfBounds(currentLen)) =>
                  promise.tryFailure(
                    new IndexOutOfBoundsException(
                      s"Reading $len from offset $offset of '$path' but length is $currentLen."
                    )
                  )
                case Right(byteVector) => promise.trySuccess(byteVector)
              }
            case None => promise.tryFailure(new IllegalStateException("TODO"))
          }
      }

    }

    private def dealWithWrite(msg: Message.Write): Unit = {
      val Message.Write(channel, path, offset, block) = msg
      val currentState = state.get()
      currentState match {
        case State.Inactive => channel.tryFailure(new IllegalStateException("TODO"))
        case Active(_, _, files, _) =>
          files.get(path) match {
            case Some(pieceFile) => channel.tryComplete(writeBlockToFile(pieceFile, offset, block))
            case None => channel.tryFailure(new IllegalStateException("TODO"))
          }
      }
    }

    private def writeBlockToFile(torrentFile: TorrentFile, offSet: Long, data: ByteVector): Try[Unit] =
      torrentFile.write(offSet, data) match {
        case Left(TorrentFile.WriteError.Io(exception)) =>
          Failure(new IOException(s"Writing ${data.length} from offset $offSet to '${torrentFile.path}'.", exception))
        case Left(TorrentFile.WriteError.AlreadyFinalized(overlap)) =>
          Failure(
            new IllegalArgumentException(
              s"Writing ${data.length} from offset $offSet to '${torrentFile.path}', but slice already finished."
            )
          )
        case Right(_) => Success(())
      }

  }

  def createSubscriber(torrent: Torrent, outputDir: Path): Subscriber = new Subscriber {
    override def onSubscribe(s: Subscription): Unit = {
      val currentState = state.get()
      currentState match {
        case State.Inactive =>
          val threadHandle = new Writer()
          threadHandle.setName("FileSystem#WriterThread")
          threadHandle.setDaemon(false)
          val y = torrent.info.files.map { case File(_, path) => path -> TorrentFileImpl(outputDir.resolve(path)) }
          val torrentFiles = y.collect { case (path, Success(value)) => path -> value }
          val failed = y.collect { case (path, Failure(exception)) => (path, exception) }
          if (failed.nonEmpty) {
            val newState =
              Active(threadHandle, State.Demand(0, List(torrent -> 0)), torrentFiles.toMap, Map(torrent -> s))
            if (!state.compareAndSet(currentState, newState)) {
              torrentFiles.foreach { case (_, torrentFile) => torrentFile.close() }
              onSubscribe(s)
            } else {
              threadHandle.start()
              s.request(1)
            }
          } else {
            logger.error(s"Failed to create torrent files. $failed")
            s.cancel()
          }

        case State.Active(threadHandle, State.Demand(i, b), files, underlying) =>
          underlying.get(torrent) match {
            case Some(_) => logger.warn(s"Already subscribed to $torrent.")
            case None =>
              val y = torrent.info.files.map { case File(_, path) => path -> TorrentFileImpl(outputDir.resolve(path)) }
              val torrentFiles = y.collect { case (path, Success(value)) => path -> value }
              val failed = y.collect { case (path, Failure(exception)) => (path, exception) }
              if (failed.nonEmpty) {
                val newState =
                  Active(
                    threadHandle,
                    State.Demand(i + 1, b :+ torrent -> 1),
                    files + torrentFiles.toMap,
                    underlying + (torrent -> s)
                  )
                if (!state.compareAndSet(currentState, newState)) {
                  torrentFiles.foreach { case (_, torrentFile) => torrentFile.close() }
                  onSubscribe(s)
                } else {
                  s.request(1)
                }
              } else {
                logger.error(s"Failed to create torrent files. $failed")
                s.cancel()
              }
          }
      }
    }

    override def onNext(idx: Int, offset: Int, data: ByteVector): Future[Unit] = {
      def internal(fC: FileChunk): Future[Unit] = {
        val promise = Promise[Unit]()
        val msg = FileSystemSink.Message.Write(promise, outputDir.resolve(fC.path), offset, data)
        Try(queue.offer(msg)) match {
          case Failure(exception) =>
            Future.failed(new Exception(s"Inserting queue element concerning $torrent.FileChunk: $fC", exception))
          case Success(success) if !success =>
            Future.failed(new Exception(s"Inserting queue element concerning $torrent. FileChunk: $fC. Queue full."))
          case Success(success) if success => promise.future
        }
      }
      torrent.fileChunks(idx, offset, data) match {
        case Some(fileChunks) => fileChunks.map(internal).toList.sequence.map(_ => ()).andThen(releaseAndSignal)
        case None => Future.successful(())
      }
    }

    override def onComplete(): Unit = logger.info("TODO.....")

    override def read(bR: Peer.BlockRequest): Future[ByteVector] = {
      def internal(fC: FileSlice): Future[ByteVector] = {
        val promise = Promise[ByteVector]()
        val msg = FileSystemSink.Message.Read(promise, outputDir.resolve(fC.path), bR.offSet, bR.length)
        Try(queue.offer(msg)) match {
          case Failure(exception) =>
            Future.failed(new Exception(s"Inserting queue element concerning $torrent.FileChunk: $fC", exception))
          case Success(success) if !success =>
            Future.failed(new Exception(s"Inserting queue element concerning $torrent. FileChunk: $fC. Queue full."))
          case Success(success) if success => promise.future
        }
      }

      torrent.fileSlices(bR.index, bR.offSet, bR.length) match {
        case Some(fileSlices) => fileSlices.map(internal).toList.sequence.map(ByteVector.concat)
        case None => Future.successful(ByteVector.empty)
      }
    }
  }

  private def decreaseDemandByOne(): Unit = {
    val currentState = state.get()
    currentState match {
      case State.Inactive => logger.info("TODO... decreaseDemand")
      case Active(_, State.Demand(pendingTotal, pending), _, underlying) =>
        logger.info("TODOODODOOD")
    }
  }

  private def releaseAndSignal[T]: PartialFunction[Try[T], Unit] = {
    case Failure(exception) =>
      logger.error("TODOODOD", exception)
      decreaseDemandByOne()
    case Success(_) => decreaseDemandByOne()
  }

  def shutdown(): Unit = {
    logger.error("TODO.....Shutting down.")
  }
}

object FileSystemSink {

  private sealed trait State

  object State {
    case object Inactive extends State
    case class Active(
        writerThread: Thread,
        demand: Demand,
        files: Map[Path, TorrentFile],
        underlying: Map[Torrent, Subscription]
    ) extends State
    case class Demand(pendingTotal: Int, pending: List[(Torrent, Int)])
  }
  private sealed trait Message
  private object Message {
    case class Write(channel: Promise[Unit], path: Path, offset: Long, data: ByteVector) extends Message
    case class Read(channel: Promise[ByteVector], path: Path, offset: Long, len: Long) extends Message
  }

  def apply(implicit ev: ExecutionContext): FileSystemSink = {
    new FileSystemSink(new LinkedBlockingQueue[Message](-1), new AtomicReference[State](State.Inactive))
  }

}
