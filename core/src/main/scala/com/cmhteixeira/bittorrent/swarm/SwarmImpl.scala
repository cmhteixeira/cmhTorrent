package com.cmhteixeira.bittorrent.swarm

import cats.implicits.catsStdInstancesForFuture
import com.cmhteixeira.bittorrent.peerprotocol.Peer
import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import com.cmhteixeira.bittorrent.swarm.State.PieceState._
import com.cmhteixeira.bittorrent.swarm.State.{Active, PeerState => InnerState, Pieces}
import com.cmhteixeira.bittorrent.swarm.Swarm.{PeerState, PieceState}
import com.cmhteixeira.bittorrent.swarm.SwarmImpl.maxBlocksAtOnce
import com.cmhteixeira.bittorrent.swarm.Torrent.FileChunk
import com.cmhteixeira.bittorrent.tracker.Tracker
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector

import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.util.{Failure, Random, Success}

private[bittorrent] class SwarmImpl private (
    peers: AtomicReference[Map[InetSocketAddress, InnerState]],
    pieces: AtomicReference[State.Pieces],
    torrent: Torrent,
    tracker: Tracker,
    scheduler: ScheduledExecutorService,
    upsertPeers: UpsertPeers,
    config: SwarmImpl.Configuration,
    randomGen: Random,
    fileManager: TorrentFileManager,
    mainExecutor: ExecutionContext
) extends Swarm {
  private val logger = LoggerFactory.getLogger(s"Swarm.${torrent.infoHash}")
  tracker.submit(torrent.toTrackerTorrent)
  scheduler.scheduleAtFixedRate(upsertPeers, 0, 10, TimeUnit.SECONDS)

  mainExecutor.execute(new Runnable { def run(): Unit = updatePieces() })

  override def downloadCompleted: Future[Path] = ???

  override def getPeers: Map[InetSocketAddress, Swarm.PeerState] =
    peers.get().map {
      case (peerSocket, State.Tried(triedLast)) => peerSocket -> PeerState.Tried(triedLast)
      case (peerSocket, State.Active(peer)) => peerSocket -> PeerState.On(peer.getState)
    }

  override def getPieces: List[Swarm.PieceState] =
    pieces.get().underlying.map {
      case (_, State.PieceState.Downloading(blocks)) =>
        PieceState.Downloading(
          blocks.size,
          blocks.count {
            case (_, BlockState.WrittenToFile) => true
            case _ => false
          }
        )
      case (_, State.PieceState.Downloaded) => PieceState.Downloaded
      case (_, State.PieceState.Missing) => PieceState.Missing
    }
  override def close: Unit = println("Closing this and that")

  private def chooseNewBlocksToDownload(
      currentState: Pieces
  ): (List[BlockRequest], List[(Int, List[(BlockRequest, Boolean)])]) = {
    val missingBlocks = currentState.missingBlocks
    val blocksToDownload = maxBlocksAtOnce - currentState.numBlocksDownloading
    val numBlocksExistingPieces = math.min(missingBlocks.size, blocksToDownload)
    val numBlocksNewPieces = blocksToDownload - numBlocksExistingPieces

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

    (newBlocks, newPieces)
  }

  @tailrec
  private def updatePieces(): Unit = {
    val currentState = pieces.get()
    val numBlocksDownloading = currentState.numBlocksDownloading
    if (numBlocksDownloading >= maxBlocksAtOnce) logger.info(s"Downloading $numBlocksDownloading blocks already.")
    else {
      val (newBlocksToDownload, newPiecesToDownload) = chooseNewBlocksToDownload(currentState)
      (for {
        state1 <- currentState.markBlocksForDownload(newBlocksToDownload)
        state2 <- state1.markPiecesForDownload(newPiecesToDownload)
      } yield state2) match {
        case Left(value) => logger.warn(s"No downloading of new pieces: $value")
        case Right(newState) =>
          if (!pieces.compareAndSet(currentState, newState)) updatePieces()
          else {
            newBlocksToDownload.foreach(blockRequest => downloadBlock(blockRequest))
            newPiecesToDownload.foreach {
              case (_, blocks) =>
                blocks.foreach {
                  case (blockRequest, download) if download => downloadBlock(blockRequest)
                  case _ => ()
                }
            }
          }
      }
    }
  }

  private def downloadBlock(blockR: BlockRequest): Unit = {
    val relevantPeers = peers.get().collect { case (_, Active(peer)) if peer.hasPiece(blockR.index) => peer }.toList
    randomGen.shuffle(relevantPeers) match {
      case Nil =>
        logger.info(s"Trying to download '$blockR'. No peer has piece.")
        markAsMissing(blockR)
        scheduler.schedule(new Runnable { def run(): Unit = updatePieces() }, 60, TimeUnit.SECONDS)
      case headPeer :: xs => onceBlockArrives(blockR, timeout(headPeer.download(blockR)))
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
      blockRequest: BlockRequest,
      eventualBlock: Future[ByteVector]
  ): Unit =
    eventualBlock.onComplete {
      case Failure(exception) =>
        logger.warn(s"Failed to download '$blockRequest'.", exception)
        markAsMissing(blockRequest)
        updatePieces()
      case Success(pieceBlock) =>
        torrent.fileForBlock(blockRequest.index, blockRequest.offSet, pieceBlock) match {
          case Some(files) =>
            implicit val ec: ExecutionContext = mainExecutor
            files.traverse {
              case FileChunk(path, offset, block) => fileManager.write(path, offset, block)
            } onComplete {
              case Success(_) =>
                blockDone(blockRequest)
                updatePieces()
              case Failure(exception) =>
                logger.error(s"Failed to write block '$blockRequest'.", exception)
                markAsMissing(blockRequest)
                updatePieces()
            }
          case None =>
            logger.error(s"OMGGGGGGGGG: '$blockRequest'.")
            markAsMissing(blockRequest)
            updatePieces()
        }
    }(mainExecutor)

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
  private def blockDone(blockRequest: BlockRequest): Unit = {
    val currentState = pieces.get()
    val BlockRequest(index, offSet, blockLength) = blockRequest
    currentState.blockCompleted(blockRequest) match {
      case Left(value) => logger.error(s"Very serious error: '$value'.")
      case Right(newState) =>
        if (!pieces.compareAndSet(currentState, newState)) blockDone(blockRequest)
        else
          newState.index(blockRequest.index).get match {
            case Downloaded => logger.info(s"Wrote last block of piece $index. Offset: $offSet, length: $blockLength.")
            case _ => logger.info(s"Wrote block. Piece: $index, offset: $offSet, length: $blockLength.")
          }
    }
  }
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
    val peers = new AtomicReference[Map[InetSocketAddress, InnerState]](Map.empty)
    val writerThread = WriterThread(downloadDir, mainExecutor)

    new SwarmImpl(
      peers = peers,
      pieces = new AtomicReference[State.Pieces](Pieces.from(torrent.info.pieces)),
      torrent = torrent,
      tracker = tracker,
      scheduler = scheduler,
      upsertPeers = UpsertPeers(peers, peerFactory, torrent, tracker),
      config = Configuration(downloadDir, blockSize),
      randomGen = random,
      fileManager = writerThread.get,
      mainExecutor = mainExecutor
    )
  }
}
