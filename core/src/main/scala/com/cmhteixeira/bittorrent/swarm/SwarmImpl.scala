package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.peerprotocol.Peer
import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import com.cmhteixeira.bittorrent.swarm.State.{Active, BlockState, Downloading, Missing, PeerState, Pieces}
import com.cmhteixeira.bittorrent.swarm.Swarm.Tried
import com.cmhteixeira.bittorrent.swarm.SwarmImpl.maxBlocksAtOnce
import com.cmhteixeira.bittorrent.tracker.Tracker
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector

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

  run()

  override def downloadCompleted: Future[Path] = ???

  override def getPeers: Map[InetSocketAddress, Swarm.PeerState] =
    peers.get().map {
      case (peerSocket, State.Tried(triedLast)) => peerSocket -> Tried(triedLast)
      case (peerSocket, State.Active(peer)) => peerSocket -> Swarm.On(peer.getState)
    }

  override def getPieces: List[Swarm.PieceState] =
    pieces.get().underlying.map {
      case (_, State.Downloading(_, blocks)) =>
        Swarm.Downloading(
          blocks.size,
          blocks.count {
            case (_, BlockState.WrittenToFile) => true
            case _ => false
          }
        )
      case (_, State.Downloaded(path)) => Swarm.Downloaded(path)
      case (_, State.Missing) => Swarm.Missing
    }
  override def close: Unit = println("Closing this and that")

  private def run(): Unit =
    Try {
      logger.info("Updating pieces ...")
      updatePieces()
    } match {
      case Failure(exception) => logger.error("Updating pieces.", exception)
      case Success(_) => logger.info("Completed updating pieces.")
    }

  private def updatePieces(): Unit = {
    val currentState = pieces.get()
    val numBlocksDownloading = currentState.numBlocksDownloading
    if (numBlocksDownloading >= maxBlocksAtOnce)
      logger.info(s"Downloading $numBlocksDownloading blocks already. Waiting until some finish.")
    else (1 to maxBlocksAtOnce - numBlocksDownloading).foreach(_ => downloadNewBlock())
  }

  @tailrec
  private def downloadNewBlock(): Unit =
    pieces.get().missingBlocksOfStartedPieces.headOption match {
      case Some(blockR) => downloadBlock(blockR)
      case None =>
        if (processNewPiece()) downloadNewBlock()
    }

  private def downloadBlock(blockR: BlockRequest): Unit = {
    val currentPeers = peers.get()
    val pieceIndex = blockR.index
    val peersHave = currentPeers.collect { case (_, Active(peer)) if peer.hasPiece(pieceIndex) => peer }.toList
    if (peersHave.isEmpty) {
      logger.info(s"Trying to download '$blockR'. No peer has piece.")
      Thread.sleep(1000) // todo: How to fix this?
      downloadNewBlock()
    } else {
      val (_, peer) = peersHave.lift(randomGen.nextInt(peersHave.size)).map(pieceIndex -> _).get
      val eventualBlock = timeout(peer.download(blockR))
      editStateBlockR(blockR, eventualBlock) match {
        case Left(value) =>
          logger.error(s"Error registering new block download: '$value'")
          updatePieces()
        case Right(pieceFile) => onceBlockArrives(pieceFile, blockR, eventualBlock)
      }
    }
  }

  private def editStateBlockR(
      blockRequest: BlockRequest,
      eventualBlock: Future[ByteVector]
  ): Either[Pieces.Error, PieceFile] = {
    val currentState = pieces.get()
    currentState.askNewBlock(blockRequest) match {
      case Left(error) => Left(error)
      case Right((Downloading(pieceFile, _), newState)) =>
        if (!pieces.compareAndSet(currentState, newState)) editStateBlockR(blockRequest, eventualBlock)
        else Right(pieceFile)
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

  private def processNewPiece(): Boolean = {
    val currentState = pieces.get()
    currentState.missingPieces.headOption match {
      case Some(pieceIndex) =>
        val allBlocks = torrent
          .splitInBlocks(pieceIndex, config.blockSize)
          .map { case (offset, len) => BlockRequest(pieceIndex, offset, len) }

        val filePath = fileName(pieceIndex)
        val sizeFile = torrent.pieceSize(pieceIndex)
        logger.info(s"Creating file '${filePath.toAbsolutePath}' for piece $pieceIndex with $sizeFile bytes.")

        val newState = (for {
          backingFile <- PieceFileImpl(filePath)
          _ <- backingFile.write(new Array[Byte](sizeFile))
        } yield backingFile) match {
          case Failure(exception) =>
            logger.info(s"Creating file '$filePath' for piece $pieceIndex.", exception)
            currentState.updateState(pieceIndex, Missing)
          case Success(pieceFile) =>
            currentState.updateState(
              pieceIndex,
              Downloading(pieceFile, allBlocks.map(blockRequest => blockRequest -> BlockState.Missing).toMap)
            )
        }

        if (!pieces.compareAndSet(currentState, newState)) processNewPiece()
        else {
          logger.info(s"Started downloading piece $pieceIndex.")
          true
        }

      case None =>
        logger.warn("No pieces left to download.")
        false
    }
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
