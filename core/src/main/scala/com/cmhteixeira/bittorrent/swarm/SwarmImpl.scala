package com.cmhteixeira.bittorrent.swarm

import cats.implicits.{catsStdInstancesForFuture, toTraverseOps}
import com.cmhteixeira.bittorrent.peerprotocol.Peer
import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import com.cmhteixeira.bittorrent.swarm.SwarmImpl.PieceState.{BlockState, Downloaded}
import com.cmhteixeira.bittorrent.swarm.SwarmImpl.{PeerFactory, maxBlocksAtOnce}
import com.cmhteixeira.bittorrent.swarm.Torrent.FileChunk
import com.cmhteixeira.bittorrent.tracker.Tracker
import com.cmhteixeira.cmhtorrent.PieceHash
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector

import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.util.{Failure, Success}

private class SwarmImpl private (
    state: AtomicReference[SwarmImpl.State],
    torrent: Torrent,
    tracker: Tracker,
    scheduler: ScheduledExecutorService,
    config: SwarmImpl.Configuration,
    fileManager: TorrentFileManager,
    mainExecutor: ExecutionContext,
    peerFactory: PeerFactory
) extends Swarm {
  private val logger = LoggerFactory.getLogger(s"Swarm.${torrent.infoHash.hex.take(6)}")
  tracker
    .submit(torrent.toTrackerTorrent)
    .subscribe(new TrackerSubscriber)

  private def removePeer(peer: Peer): Unit = {
    val currentState = state.get()
    currentState match {
      case SwarmImpl.Closed => logger.warn(s"Removing peer '$peer', but swarm already closed.")
      case active: SwarmImpl.Active => if (!state.compareAndSet(currentState, active.removePeer(peer))) removePeer(peer)
    }
  }

  private class PeerSubscriber(peer: Peer) extends Peer.Subscriber {

    private def chocked(isTrue: Boolean): Unit = {
      val currentState = state.get()
      currentState match {
        case SwarmImpl.Closed => logger.warn(s"Chocking peer '$peer', but swarm already closed.")
        case active: SwarmImpl.Active =>
          if (!state.compareAndSet(currentState, if (isTrue) active.chocked(peer) else active.unChocked(peer)))
            chocked(isTrue)
      }
    }

    private def hasPieceA(idx: Int): Unit = {
      val currentState = state.get()
      currentState match {
        case SwarmImpl.Closed => logger.warn(s"Register peer '$peer' has piece $idx, but swarm already closed.")
        case active: SwarmImpl.Active =>
          if (!state.compareAndSet(currentState, active.peerHasPiece(peer, idx))) hasPiece(idx)
      }
    }

    override def onError(e: Exception): Unit = {
      logger.info("Removing peer.", e)
      removePeer(peer)
    }
    override def chocked(): Unit = {
      logger.info(s"Peer '$peer' is choked.")
      chocked(true)
    }
    override def unChocked(): Unit = {
      logger.info(s"Peer '$peer' is unChoked.")
      chocked(false)
    }
    override def hasPiece(idx: Int): Unit = {
      logger.info(s"Peer '$peer' has piece $idx")
      hasPieceA(idx)
      run()
    }
  }

  private class TrackerSubscriber extends Tracker.Subscriber {

    private def addSubscription(s: Tracker.Subscription): Unit = {
      val currentState = state.get()
      currentState match {
        case active @ SwarmImpl.Active(_, _, _, _, None) =>
          if (!state.compareAndSet(currentState, active.copy(trackerSubscription = Some(s)))) addSubscription(s)
        case _ => ()
      }
    }
    override def onSubscribe(s: Tracker.Subscription): Unit = {
      addSubscription(s)
      logger.info(s"Started subscription for tracker for '${torrent.infoHash}'")
    }

    override def onNext(t: InetSocketAddress): Unit = {
      val currentState = state.get()
      currentState match {
        case SwarmImpl.Closed => logger.warn("Trying to add, but swarm is closed.")
        case active @ SwarmImpl.Active(unconnectedPeers, peersToState, _, _, _) =>
          implicit val ec = mainExecutor
          if (unconnectedPeers.contains(t) || peersToState.exists { case (peer, _) => peer.address == t }) ()
          else {
            val newPeer = peerFactory(t)
            if (!state.compareAndSet(currentState, active.addNewPeer(newPeer))) {
              newPeer.close()
              onNext(t)
            } else
              newPeer.subscribe(new PeerSubscriber(newPeer)) onComplete {
                case Success(_) => logger.info(s"Added new peer for '$t'.")
                case Failure(exception) =>
                  logger.info(s"Subscribing to peer '$t'", exception)
                  removePeer(newPeer)
              }
          }
      }

    }
    override def onError(t: Throwable): Unit = logger.error("Tracker stopped subscription.", t)
  }

  override def close(): Unit = {
    logger.info("Shutting down.")
    state.get() match {
      case SwarmImpl.Closed => logger.warn("Closing, but already closed. Doing nothing.")
      case SwarmImpl.Active(_, peersToState, _, _, trackerSubscription) =>
        state.set(SwarmImpl.Closed)
        peersToState.keys.foreach(_.close())
        trackerSubscription match {
          case Some(trackerSubx) => trackerSubx.cancel()
          case None => logger.info("No tracker subscription to which to signal cancellation.")
        }
    }
  }

  private def chooseBlock(): Either[String, (BlockRequest, Peer)] = {
    val currentState = state.get()
    currentState match {
      case SwarmImpl.Closed => Left("Swarm already closed.")
      case active: SwarmImpl.Active =>
        chooseBlockToDownload(active).toRight("Could not choose block").flatMap { case (blockR, peer) =>
          active.markBlockForDownload(blockR) match {
            case Left(_) => Left("Correct later....")
            case Right(newState) =>
              if (!state.compareAndSet(currentState, newState)) chooseBlock()
              else Right((blockR, peer))
          }
        }
    }

  }

  private def run(): Future[Unit] = {
    implicit val ec: ExecutionContext = mainExecutor
    chooseBlock() match {
      case Left(value) =>
        logger.info(s"Stopping task: $value")
        Future.unit
      case Right((blockR, peer)) =>
        (for {
          byteVector <- timeout(peer.download(blockR))
          fileChunks <- torrent.fileChunks(blockR.index, blockR.offSet, byteVector) match {
            case Some(fileChunks) => Future.successful(fileChunks)
            case None => Future.failed(new Exception("kabooom!"))
          }
          _ <- fileChunks.traverse { case FileChunk(path, offset, block) => fileManager.write(path, offset, block) }
          newState4Piece <- setStateToCompleted(blockR)
          _ <-
            if (newState4Piece.allBlocksDownloaded) hashCheck(blockR, torrent.info.pieces.toList(blockR.index))
            else Future.unit
          _ <- state.get() match {
            case SwarmImpl.Closed => Future.unit
            case active: SwarmImpl.Active =>
              val newRunsToSpawn = maxBlocksAtOnce - active.numBlocksDownloading
              if (newRunsToSpawn > 0) (1 to newRunsToSpawn).toList.map(_ => run()).sequence else Future.unit
          }
        } yield ()).andThen {
          case Failure(exception) =>
            markAsFailed(blockR)
            logger.warn("Finished", exception)
          case Success(_) => logger.info("Finished")
        }
    }
  }

  private def hashCheck(blockR: BlockRequest, hash: PieceHash): Future[Boolean] =
    fileManager.complete(torrent.fileSlices(blockR.index).fold[List[Torrent.FileSlice]](List())(_.toList).map {
      case Torrent.FileSlice(path, offset, len) => TorrentFileManager.FileSlice(path, offset, len)
    }) { content =>
      ByteVector(MessageDigest.getInstance("SHA-1").digest(content.toArray)) == ByteVector(
        hash.bytes
      )
    }

  private def chooseBlockToDownload(
      active: SwarmImpl.Active
  ): Option[(BlockRequest, Peer)] =
    active.piecesToState
      .collect { case (idx, downloading @ SwarmImpl.PieceState.Downloading(_)) =>
        (idx, active.piecesToPeers(idx), downloading)
      }
      .toList
      .filter { case (_, peers, _) => peers.nonEmpty }
      .sortBy { case (_, peers, _) => peers.size }
      .headOption
      .flatMap { case (_, value, SwarmImpl.PieceState.Downloading(blocks)) =>
        blocks
          .find {
            case (_, BlockState.Missing) => true
            case _ => false
          }
          .map { case (bR, _) => (bR, value.head) }
      }

  private def timeout[A](fut: Future[A]): Future[A] = { // TODO: For shutdown, need to take care of this.
    val promise = Promise[A]()
    scheduler.schedule( // todo: Check if usage of try-complete is appropriate
      new Runnable { override def run(): Unit = promise.tryFailure(new TimeoutException("Timeout after 30 seconds.")) },
      30,
      TimeUnit.SECONDS
    )
    fut.onComplete(promise.tryComplete)(
      mainExecutor
    ) // todo: Check if usage of try-complete is appropriate (according with scala-docs, makes programs non-deterministic (which I think is fair))
    promise.future
  }

  private def markAsFailed(blockRequest: BlockRequest): Unit = {
    val currentState = state.get()
    currentState match {
      case SwarmImpl.Closed => logger.warn(s"Marking '$blockRequest' as failed, but swarm is closed.")
      case active: SwarmImpl.Active =>
        active.markAsFailed(blockRequest.index, blockRequest) match {
          case Left(error) => logger.error(s"Couldn't mark as missing: '$error'.")
          case Right(newState) =>
            if (!state.compareAndSet(currentState, newState)) markAsFailed(blockRequest)
            else logger.info(s"Marked block $blockRequest as missing.")
        }
    }

  }

  @tailrec
  private def setStateToCompleted(blockRequest: BlockRequest): Future[SwarmImpl.PieceState] = {
    val currentState = state.get()
    currentState match {
      case SwarmImpl.Closed => Future.failed(new Exception(s"Mark block '$blockRequest' to completed but swarm closed"))
      case active: SwarmImpl.Active =>
        active.blockCompleted(blockRequest) match {
          case Left(value) => Future.failed(new Exception(s"Something bad: $value"))
          case Right(newState) =>
            if (!state.compareAndSet(currentState, newState)) setStateToCompleted(blockRequest)
            else Future.successful(newState.piecesToState(blockRequest.index))
        }
    }

  }
  override def trackerStats: Tracker.Statistics =
    tracker.statistics.get(torrent.infoHash) match {
      case Some(stats) => stats
      case None =>
        logger.warn("Tracker could not find statistics for this torrent. Very bad.")
        Tracker.Statistics(Tracker.Summary(0, 0, 0, 0, 0), Map.empty)
    }
  override def getPieces: List[Swarm.PieceState] =
    state.get() match {
      case SwarmImpl.Closed => List.empty
      case SwarmImpl.Active(_, _, _, piecesToState, _) =>
        piecesToState.toList.sortBy { case (i, _) => i }.map {
          case (_, SwarmImpl.PieceState.Downloading(blocks)) =>
            Swarm.PieceState.Downloading(
              blocks.size,
              blocks.count {
                case (_, BlockState.Asked) => false
                case (_, BlockState.Missing) => false
                case (_, BlockState.WrittenToFile) => true
              }
            )
          case (_, SwarmImpl.PieceState.Downloaded) => Swarm.PieceState.Downloaded
        }
    }

  override def getPeers: List[Swarm.PeerState] = {
    state.get() match {
      case SwarmImpl.Closed => List.empty
      case SwarmImpl.Active(unconnectedPeers, peersToState, _, _, _) =>
        peersToState.values.toList.map { case SwarmImpl.PeerState(isChoked, _, _, numPieces) =>
          Swarm.PeerState.Connected(isChoked, numPieces.size)
        } ::: (1 to unconnectedPeers.size).toList.map(_ => Swarm.PeerState.Unconnected)
    }
  }
}

object SwarmImpl {

  type PeerFactory = InetSocketAddress => Peer

  private val maxBlocksAtOnce = 1
  case class Configuration(downloadDir: Path, blockSize: Int)

  private[swarm] case class PeerState(chocked: Boolean, uploaded: Long, downloaded: Long, pieces: Set[Int])

  object PeerState {
    private[swarm] val initialState: PeerState = PeerState(chocked = true, uploaded = 0, downloaded = 0, pieces = Set())
  }

  sealed trait State

  case object Closed extends State

  case class Active(
      unconnectedPeers: Set[InetSocketAddress],
      peersToState: Map[Peer, SwarmImpl.PeerState],
      piecesToPeers: Map[Int, Set[Peer]],
      piecesToState: Map[Int, PieceState],
      trackerSubscription: Option[Tracker.Subscription]
  ) extends State {

    def peerHasPiece(peer: Peer, idx: Int): Active = {
      val peerState = peersToState(peer)
      copy(
        peersToState = peersToState + (peer -> peerState.copy(pieces = peerState.pieces + idx)),
        piecesToPeers = if (peerState.chocked) piecesToPeers else piecesToPeers + (idx -> (piecesToPeers(idx) + peer))
      )
    }

    def addNewPeer(peer: Peer): Active = copy(peersToState = peersToState + (peer -> SwarmImpl.PeerState.initialState))

    def removePeer(peer: Peer): Active =
      copy(
        unconnectedPeers = unconnectedPeers + peer.address,
        peersToState = peersToState - peer,
        piecesToPeers = piecesToPeers.map { case p @ (index, peers) =>
          if (peers.contains(peer)) index -> (peers - peer) else p
        }
      )

    def unChocked(peer: Peer): Active = {
      val peerState = peersToState(peer)
      copy(
        peersToState = peersToState + (peer -> peerState.copy(chocked = false)),
        piecesToPeers = piecesToPeers.map { case pair @ (index, peerSet) =>
          if (peerState.pieces.contains(index)) index -> (peerSet + peer) else pair
        }
      )
    }

    def chocked(peer: Peer): Active =
      copy(
        peersToState = peersToState + (peer -> peersToState(peer).copy(chocked = true)),
        piecesToPeers = piecesToPeers.map { case (index, peers) => index -> (peers - peer) }
      )

    def updateState(pieceIndex: Int, newState: PieceState): Active =
      copy(piecesToState = piecesToState.updated(pieceIndex, newState))

    def numBlocksDownloading: Int =
      piecesToState.foldLeft(0) {
        case (acc, (_, SwarmImpl.PieceState.Downloading(blocks))) =>
          acc + blocks.count {
            case (_, BlockState.Asked) => true
            case _ => false
          }
        case (acc, _) => acc
      }

    def markBlockForDownload(blockRequest: BlockRequest) =
      piecesToState.get(blockRequest.index) match {
        case Some(Downloaded) => Left(Active.OmgError(s"Piece ${blockRequest.index} already downloaded."))
        case Some(d @ SwarmImpl.PieceState.Downloading(blocks)) =>
          val newState = d.copy(blocks = blocks + (blockRequest -> BlockState.Asked))
          Right(newState, updateState(blockRequest.index, newState))
          blocks.get(blockRequest) match {
            case Some(BlockState.Missing) =>
              val newState = d.copy(blocks = blocks + (blockRequest -> BlockState.Asked))
              Right(updateState(blockRequest.index, newState))
            case Some(BlockState.Asked) =>
              Left(Active.OmgError(s"Block $blockRequest already asked."))
            case Some(BlockState.WrittenToFile) =>
              Left(Active.OmgError(s"Block $blockRequest already written to file."))
            case None => Left(Active.OmgError(s"Block $blockRequest not found."))
          }
        case None => Left(Active.OmgError(s"Piece index ${blockRequest.index} not found."))
      }

    def markBlocksForDownload(blockRequests: List[BlockRequest]): Either[Active.OmgError, Active] =
      blockRequests match {
        case Nil => Right(this)
        case ::(head, tl) =>
          markBlockForDownload(head) match {
            case Left(value) => Left(value)
            case Right(value) => value.markBlocksForDownload(tl)
          }
      }

    def markAsFailed(piece: Int, blockRequest: BlockRequest): Either[Active.Error, Active] = {
      piecesToState.get(piece) match {
        case Some(Downloaded) => Left(Active.OmgError(s"Piece $piece already downloaded."))
        case Some(d @ SwarmImpl.PieceState.Downloading(blocks)) =>
          blocks.get(blockRequest) match {
            case Some(BlockState.Missing) =>
              Left(Active.OmgError(s"Block $blockRequest already set to missing."))
            case Some(BlockState.Asked) =>
              Right(updateState(piece, d.copy(blocks = blocks + (blockRequest -> BlockState.Missing))))
            case Some(BlockState.WrittenToFile) =>
              Left(Active.OmgError(s"Block $blockRequest already written to file."))
            case None => Left(Active.OmgError(s"Block $blockRequest not found."))
          }
        case None => Left(Active.OmgError(s"Piece index $piece not found."))
      }
    }

    def index(idx: Int): Option[PieceState] = piecesToState.get(idx)

    def blockCompleted(block: BlockRequest): Either[Active.Error, Active] = {
      piecesToState.get(block.index) match {
        case Some(downloading @ SwarmImpl.PieceState.Downloading(blocks)) =>
          blocks.get(block) match {
            case Some(BlockState.Missing) => Left(Active.OmgError(s"Block '$block' set to missing."))
            case Some(BlockState.Asked) =>
              val isLastBlock = blocks.count {
                case (_, BlockState.WrittenToFile) => true
                case _ => false
              } == blocks.size - 1

              if (isLastBlock)
                Right(updateState(block.index, Downloaded))
              else
                Right(updateState(block.index, downloading.copy(blocks = blocks + (block -> BlockState.WrittenToFile))))

            case Some(BlockState.WrittenToFile) => Left(Active.OmgError(s"Block '$block' already written to file"))
            case None => Left(Active.OmgError(s"Block $block not found."))
          }
        case Some(Downloaded) => Left(Active.OmgError(s"Piece index ${block.index} already downloaded."))
        case None => Left(Active.OmgError(s"Piece index ${block.index} not found."))
      }
    }
  }

  object Active {
    sealed trait Error
    case class OmgError(msg: String) extends Error

    def from(piecesToBlocks: Map[Int, Set[BlockRequest]]): State = {
      Active(
        unconnectedPeers = Set.empty,
        peersToState = Map.empty,
        piecesToPeers = piecesToBlocks.map { case (index, _) => index -> Set.empty },
        piecesToState = piecesToBlocks.map { case (index, blocks) =>
          index -> PieceState.Downloading(blocks.map(_ -> PieceState.BlockState.Missing).toMap)
        },
        trackerSubscription = None
      )
    }
  }

  sealed trait PieceState extends Product with Serializable {

    def allBlocksDownloaded: Boolean = this match {
      case SwarmImpl.PieceState.Downloading(blocks) =>
        blocks.forall {
          case (_, SwarmImpl.PieceState.BlockState.WrittenToFile) => true
          case _ => false
        }
      case PieceState.Downloaded => true

    }
  }

  object PieceState {

    case class Downloading(blocks: Map[BlockRequest, BlockState]) extends PieceState
    case object Downloaded extends PieceState
    sealed trait BlockState

    object BlockState {
      case object Missing extends BlockState
      case object Asked extends BlockState
      case object WrittenToFile extends BlockState
    }
  }

  def apply(
      tracker: Tracker,
      mainExecutor: ExecutionContext,
      scheduler: ScheduledExecutorService,
      peerFactory: PeerFactory,
      downloadDir: Path,
      blockSize: Int,
      torrent: Torrent
  ): Swarm = {
    val writerThread = WriterThread(downloadDir, mainExecutor)

    new SwarmImpl(
      state = new AtomicReference(
        Active.from(
          torrent.info.pieces.zipWithIndex
            .map { case (_, index) =>
              index -> torrent
                .splitInBlocks(index, blockSize)
                .map { case (off, len) => BlockRequest(index, off, len) }
                .toSet
            }
            .toList
            .toMap
        )
      ),
      torrent = torrent,
      tracker = tracker,
      scheduler = scheduler,
      config = Configuration(downloadDir, blockSize),
      fileManager = writerThread.get,
      mainExecutor = mainExecutor,
      peerFactory
    )
  }
}
