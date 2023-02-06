package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.peerprotocol.Peer
import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import com.cmhteixeira.bittorrent.swarm.State.PieceState.BlockState.Asked
import com.cmhteixeira.bittorrent.swarm.State.PieceState._
import com.cmhteixeira.bittorrent.swarm.State.{Active, PeerState, Pieces}
import com.cmhteixeira.bittorrent.swarm.Swarm.Tried
import com.cmhteixeira.bittorrent.swarm.SwarmImpl.maxBlocksAtOnce
import com.cmhteixeira.bittorrent.tracker.Tracker
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{LinkedBlockingQueue, ScheduledExecutorService, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.util.{Failure, Random, Success, Try}

private[bittorrent] class SwarmImpl private (
    peers: AtomicReference[Map[InetSocketAddress, PeerState]],
    pieces: AtomicReference[State.Pieces],
    torrent: Torrent,
    tracker: Tracker,
    scheduler: ScheduledExecutorService,
    upsertPeers: UpsertPeers,
    config: SwarmImpl.Configuration,
    randomGen: Random,
    writerThread: WriterThread,
    mainExecutor: ExecutionContext
) extends Swarm {
  private val logger = LoggerFactory.getLogger("Swarm")
  tracker.submit(torrent.toTrackerTorrent)
  scheduler.scheduleAtFixedRate(upsertPeers, 0, 10, TimeUnit.SECONDS)

  mainExecutor.execute(new Runnable { def run(): Unit = updatePieces() })

  override def downloadCompleted: Future[Path] = ???

  override def getPeers: Map[InetSocketAddress, Swarm.PeerState] =
    peers.get().map {
      case (peerSocket, State.Tried(triedLast)) => peerSocket -> Tried(triedLast)
      case (peerSocket, State.Active(peer)) => peerSocket -> Swarm.On(peer.getState)
    }

  override def getPieces: List[Swarm.PieceState] =
    pieces.get().underlying.map {
      case (_, State.PieceState.Downloading(_, blocks)) =>
        Swarm.Downloading(
          blocks.size,
          blocks.count {
            case (_, BlockState.WrittenToFile) => true
            case _ => false
          }
        )
      case (_, State.PieceState.Downloaded(path)) => Swarm.Downloaded(path)
      case (_, State.PieceState.Missing) => Swarm.Missing
      case (_, State.PieceState.MarkedForDownload) => Swarm.Missing
    }
  override def close: Unit = println("Closing this and that")

  @tailrec
  private def updatePieces(): Unit = {
    val currentState = pieces.get()
    val numBlocksDownloading = currentState.numBlocksDownloading
    if (numBlocksDownloading >= maxBlocksAtOnce) logger.info(s"Downloading $numBlocksDownloading blocks already.")
    else {
      val missingBlocks = currentState.missingBlocks
      val blocksToDownload = maxBlocksAtOnce - numBlocksDownloading
      val numBlocksExistingPieces = math.min(missingBlocks.size, blocksToDownload)
      val numBlocksNewPieces = blocksToDownload - numBlocksExistingPieces
      logger.info(
        s"Missing blocks: ${missingBlocks.size}. Blocks to download: $blocksToDownload. Existing Pieces: $numBlocksExistingPieces. NewPieces: $numBlocksNewPieces"
      )
      val newPieces = randomGen
        .shuffle(currentState.missingPieces)
        .map(a => a -> torrent.splitInBlocks(a, config.blockSize))
        .foldLeft[List[(Int, List[(BlockRequest, Boolean)])]](List.empty) {
          case (acc, (pieceIndex, blockPartition)) =>
            val blockRequests = blockPartition.map { case (offSet, len) => BlockRequest(pieceIndex, offSet, len) }
            val totalNewBlocks = acc.flatMap(_._2).size
            if (totalNewBlocks >= numBlocksNewPieces) acc
            else {
              val blocks = math.min(blockRequests.size, numBlocksNewPieces - totalNewBlocks)
              val (toDownloadNow, toDownloadLater) = blockRequests.splitAt(blocks)
              acc :+ (pieceIndex, toDownloadNow.map(_ -> true) ::: toDownloadLater.map(_ -> false))
            }
        }

      val newBlocks = randomGen.shuffle(missingBlocks).take(numBlocksExistingPieces)
      (for {
        state1 <- currentState.markBlocksForDownload(newBlocks.map(_._2))
        state2 <- state1.markPiecesForDownload(newPieces.map(_._1))
      } yield state2) match {
        case Left(value) => logger.warn(s"No downloading of new pieces: $value")
        case Right(newState) =>
          if (!pieces.compareAndSet(currentState, newState)) updatePieces()
          else {
            newBlocks.foreach { case (pieceFile, blockRequest) => downloadBlock(pieceFile, blockRequest) }
            newPieces.foreach {
              case (pieceIndex, blocks) =>
                createNewPiece(pieceIndex, blocks) match {
                  case Failure(exception) => logger.warn("OMG. what is this", exception)
                  case Success(pieceFile) =>
                    blocks.foreach {
                      case (blockRequest, shouldDownload) =>
                        if (shouldDownload) downloadBlock(pieceFile, blockRequest)
                    }
                }
            }
          }
      }
    }
  }

  private def createNewPiece(pieceIndex: Int, blocksToDownload: List[(BlockRequest, Boolean)]): Try[PieceFile] = {
    val currentState = pieces.get()
    val filePath = fileName(pieceIndex)
    val sizeFile = torrent.pieceSize(pieceIndex)
    logger.info(s"Creating file '${filePath.toAbsolutePath}' for piece $pieceIndex with $sizeFile bytes.")

    (for {
      backingFile <- PieceFileImpl(filePath)
      _ <- backingFile.write(new Array[Byte](sizeFile))
    } yield backingFile) match {
      case Failure(exception) =>
        val msg = s"Creating file '$filePath' for piece $pieceIndex"
        logger.warn(msg, exception)
        Failure(new IOException(msg, exception))
      case Success(pieceFile) =>
        val newState = currentState.updateState(
          pieceIndex,
          Downloading(
            file = pieceFile,
            blocks = blocksToDownload.map {
              case (blockRequest, downloadNow) =>
                if (downloadNow) blockRequest -> Asked
                else blockRequest -> BlockState.Missing
            }.toMap
          )
        )
        if (!pieces.compareAndSet(currentState, newState)) createNewPiece(pieceIndex, blocksToDownload)
        else Success(pieceFile)
    }
  }

  private def downloadBlock(pieceFile: PieceFile, blockR: BlockRequest): Unit = {
    val relevantPeers = peers.get().collect { case (_, Active(peer)) if peer.hasPiece(blockR.index) => peer }.toList
    randomGen.shuffle(relevantPeers) match {
      case Nil =>
        logger.info(s"Trying to download '$blockR'. No peer has piece.")
        markAsMissing(blockR)
        // todo: Can this lead to too many recursive calls will lead to StackOverflow.
        scheduler.schedule(new Runnable { def run(): Unit = updatePieces() }, 60, TimeUnit.SECONDS)
      case headPeer :: xs => onceBlockArrives(pieceFile, blockR, timeout(headPeer.download(blockR)))
    }
  }

  private def timeout[A](fut: Future[A]): Future[A] = {
    val promise = Promise[A]()
    scheduler.schedule( //todo: Check if usage of try-complete is appropriate
      new Runnable { override def run(): Unit = promise.tryFailure(new TimeoutException("Timeout after 30 seconds.")) },
      30,
      TimeUnit.SECONDS
    )

    fut.onComplete(promise.tryComplete)(
      mainExecutor
    ) //todo: Check if usage of try-complete is appropriate (according with scala-docs, makes programs non-deterministic (which I think is fair))
    promise.future
  }

  private def onceBlockArrives(
      backingFile: PieceFile,
      blockRequest: BlockRequest,
      eventualBlock: Future[ByteVector]
  ): Unit = {
    val BlockRequest(_, offSet, _) = blockRequest
    val newPromise = Promise[Unit]()
    eventualBlock.onComplete {
      case Failure(exception) =>
        logger.warn(s"Failed to download '$blockRequest'.", exception)
        markAsMissing(blockRequest)
        updatePieces()
      case Success(pieceBlock) =>
        writerThread.add(WriterThread.Message(newPromise, offSet, backingFile, pieceBlock))
    }(mainExecutor)
    newPromise.future.onComplete {
      case Failure(exception) =>
        logger.error(s"Failed to write block '$blockRequest' into file '${backingFile.path}'.", exception)
        markAsMissing(blockRequest)
        updatePieces()
      case Success(_) =>
        blockDone(blockRequest, backingFile)
        updatePieces()
    }(mainExecutor)
  }

  private def markAsMissing(blockRequest: BlockRequest): Unit = {
    val currentState = pieces.get()
    currentState.maskAsMissing(blockRequest) match {
      case Left(error) => logger.error(s"Couldn't mark as missing: '$error'.")
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

object SwarmImpl {

  type PeerFactory = InetSocketAddress => Peer

  private val maxBlocksAtOnce = 100

  case class Configuration(downloadDir: Path, blockSize: Int)

  def apply(
      tracker: Tracker,
      mainExecutor: ExecutionContext,
      scheduler: ScheduledExecutorService,
      peerFactory: PeerFactory,
      random: Random,
      downloadDir: Path,
      blockSize: Int,
      torrent: Torrent
  ): SwarmImpl = {
    val peers = new AtomicReference[Map[InetSocketAddress, PeerState]](Map.empty)
    val writerThread = WriterThread(mainExecutor, new LinkedBlockingQueue[WriterThread.Message]())

    new SwarmImpl(
      peers = peers,
      pieces = new AtomicReference[State.Pieces](Pieces.from(torrent.info.pieces)),
      torrent = torrent,
      tracker = tracker,
      scheduler = scheduler,
      upsertPeers = UpsertPeers(peers, peerFactory, torrent, tracker),
      config = Configuration(downloadDir, blockSize),
      randomGen = random,
      writerThread = writerThread,
      mainExecutor = mainExecutor
    )
  }
}
