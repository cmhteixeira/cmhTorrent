package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import com.cmhteixeira.bittorrent.swarm.RequestBlocks.{Configuration, maxBlocksAtOnce}
import com.cmhteixeira.bittorrent.swarm.State.{Active, BlockState, Downloading, PeerState, Pieces}
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector

import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.util.{Failure, Random, Success, Try}

private[swarm] class RequestBlocks private (
    peers: AtomicReference[Map[InetSocketAddress, PeerState]],
    pieces: AtomicReference[Pieces],
    writerThread: WriterThread,
    torrent: Torrent,
    randomGen: Random,
    config: Configuration,
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService
) extends Runnable {
  private val logger = LoggerFactory.getLogger("Swarm")

  override def run(): Unit =
    Try {
      logger.info("Updating pieces ...")
      updatePiece()
    } match {
      case Failure(exception) => logger.error("Updating pieces.", exception)
      case Success(_) => logger.info("Completed updating pieces.")
    }

  private def updatePiece(): Unit = {
    val currentState = pieces.get()
    val numBlocksDownloading = currentState.numBlocksDownloading
    if (numBlocksDownloading >= maxBlocksAtOnce)
      logger.info(s"Downloading $numBlocksDownloading blocks already. Waiting until some finish.")
    else (1 to maxBlocksAtOnce - numBlocksDownloading).foreach(_ => downloadNewBlock())
  }

  private def downloadNewBlock(): Unit =
    pieces.get().missingBlocksOfStartedPieces.headOption match {
      case Some(blockR) => downloadBlockExistingPiece(blockR)
      case None => registerNewPiece()
    }

  private def downloadBlockExistingPiece(blockR: BlockRequest): Unit = {
    val currentPeers = peers.get()
    val pieceIndex = blockR.index
    val peersHave = currentPeers.collect { case (_, Active(peer)) if peer.hasPiece(pieceIndex) => peer }.toList
    if (peersHave.isEmpty) logger.info(s"Trying to download '$blockR'.No peer has piece '$pieceIndex'.")
    else {
      val (_, peer) = peersHave.lift(randomGen.nextInt(peersHave.size)).map(pieceIndex -> _).get
      registerNewBlockDownload(blockR, timeout(peer.download(blockR)))
    }
  }

  private def timeout[A](fut: Future[A]): Future[A] = {
    val promise = Promise[A]()
    scheduler.schedule(
      new Runnable { override def run(): Unit = promise.failure(new TimeoutException("Timeout after 30 seconds.")) },
      30,
      TimeUnit.SECONDS
    )
    fut.onComplete {
      case Failure(exception) => promise.failure(exception)
      case Success(value) => promise.success(value)
    }(mainExecutor)
    promise.future
  }

  private def registerNewBlockDownload(blockRequest: BlockRequest, eventualBlock: Future[ByteVector]): Unit = {
    val currentState = pieces.get()
    currentState.askNewBlock(blockRequest) match {
      case Left(value) => logger.error(s"Error registering new block download: '$value'")
      case Right((Downloading(pieceFile, _), newState)) =>
        if (!pieces.compareAndSet(currentState, newState)) registerNewBlockDownload(blockRequest, eventualBlock)
        else scheduleWhenItCompletes(pieceFile, blockRequest, eventualBlock)
    }
  }

  private def registerNewPiece(): Unit = {
    val currentState = pieces.get()
    currentState.missingPieces.headOption match {
      case Some(pieceIndex) =>
        val allBlocks = torrent
          .splitInBlocks(pieceIndex, config.blockSize)
          .map { case (offset, len) => BlockRequest(pieceIndex, offset, len) }

        val filePath = fileName(pieceIndex)
        val backingFile = PieceFileImpl(filePath)
        val sizeFile = torrent.pieceSize(pieceIndex)
        logger.info(s"Creating file '${filePath.toAbsolutePath}' for piece $pieceIndex with $sizeFile bytes.")
        Try(backingFile.write(new Array[Byte](sizeFile))) match {
          case Failure(exception) => logger.warn("OMG, this failed", exception)
          case Success(_) => logger.info("Success")
        }
        val newState = currentState.updateState(
          pieceIndex,
          Downloading(backingFile, allBlocks.map(blockRequest => blockRequest -> BlockState.Missing).toMap)
        )
        if (!pieces.compareAndSet(currentState, newState)) registerNewPiece()
        else {
          logger.info(s"Started downloading piece $pieceIndex.")
          downloadNewBlock()
        }

      case None => logger.warn("No pieces left to download.")
    }
  }

  private def scheduleWhenItCompletes(
      backingFile: PieceFile,
      blockRequest: BlockRequest,
      eventualBlock: Future[ByteVector]
  ): Unit = {
    val BlockRequest(_, offSet, _) = blockRequest
    val newPromise = Promise[Unit]()
    eventualBlock.onComplete {
      case Failure(exception) =>
        logger.warn(s"Failed to download '$blockRequest'. Requesting again.", exception)
        markAsMissing(blockRequest)
      case Success(pieceBlock) =>
        writerThread.add(WriterThread.Message(newPromise, offSet, backingFile, pieceBlock))
    }(mainExecutor)
    newPromise.future.onComplete {
      case Failure(exception) => logger.error("OMGGGGG....", exception)
      case Success(_) =>
        blockDone(blockRequest, backingFile)
    }(mainExecutor)
  }

  private def markAsMissing(blockRequest: BlockRequest): Unit = {
    val currentState = pieces.get()
    currentState.maskAsMissing(blockRequest) match {
      case Left(error) => logger.warn(s"Couldn't mark as missing: '$error'.")
      case Right(newState) =>
        if (!pieces.compareAndSet(currentState, newState)) markAsMissing(blockRequest)
        else logger.info(s"Marked block $blockRequest as missing.")
    }
  }

  @tailrec
  private def blockDone(blockRequest: BlockRequest, backingFile: PieceFile): Unit = {
    val currentState = pieces.get()
    val BlockRequest(index, offSet, blockLength) = blockRequest
    currentState.blockCompleted(blockRequest) match {
      case Left(value) => logger.error(s"Very serious error: '$value'.")
      case Right((lastBlock, newState)) =>
        if (!pieces.compareAndSet(currentState, newState)) blockDone(blockRequest, backingFile)
        else
          lastBlock match {
            case Some(value) =>
              logger.info(
                s"Wrote last block of piece $index. Offset: $offSet, length: $blockLength. Path piece: '$value'."
              )
              backingFile.close()
            case None => logger.info(s"Wrote block. Piece: $index, offset: $offSet, length: $blockLength.")
          }
    }
  }

  private def fileName(pieceIndex: Int): Path =
    config.downloadDir.resolve(
      s"cmhTorrent-${torrent.infoHash.hex}.piece-$pieceIndex-of-${torrent.info.pieces.size - 1}"
    )
}

private[swarm] object RequestBlocks {
  private val maxBlocksAtOnce = 100

  case class Configuration(downloadDir: Path, blockSize: Int)

  def apply(
      peers: AtomicReference[Map[InetSocketAddress, PeerState]],
      pieces: AtomicReference[Pieces],
      writerThread: WriterThread,
      torrent: Torrent,
      random: Random,
      config: Configuration,
      mainExecutor: ExecutionContext,
      scheduler: ScheduledExecutorService
  ): RequestBlocks = new RequestBlocks(peers, pieces, writerThread, torrent, random, config, mainExecutor, scheduler)
}
