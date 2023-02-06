package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import cats.implicits.toTraverseOps
import com.cmhteixeira.bittorrent.peerprotocol.Peer
import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import com.cmhteixeira.bittorrent.swarm.State.PieceState._
import com.cmhteixeira.cmhtorrent.PieceHash

import java.nio.file.Path

private[swarm] object State {
  sealed trait PeerState
  case class Tried(triedLast: Long) extends PeerState
  case class Active(peer: Peer) extends PeerState

  case class Pieces(underlying: List[(PieceHash, PieceState)]) {

    def updateState(pieceIndex: Int, newState: PieceState): Pieces =
      Pieces(underlying.zipWithIndex.map {
        case ((hash, oldState), i) if i == pieceIndex => hash -> newState
        case (tuple, _) => tuple
      })

    def missingPieces: List[Int] = underlying.zipWithIndex.collect { case ((_, Missing), index) => index }

    def numPiecesDownloading: Int =
      underlying.count {
        case (_, _: Downloading) => true
        case _ => false
      }

    def numBlocksDownloading: Int =
      underlying.foldLeft(0) {
        case (acc, (_, Downloading(_, blocks))) =>
          acc + blocks.count {
            case (_, BlockState.Asked) => true
            case _ => false
          }
        case (acc, _) => acc
      }

    def markBlockForDownload(blockRequest: BlockRequest) =
      underlying.lift(blockRequest.index) match {
        case Some((_, Missing)) =>
          Left(Pieces.OmgError(s"Entire piece ${blockRequest.index} not yet registered."))
        case Some((_, _: Downloaded)) => Left(Pieces.OmgError(s"Piece ${blockRequest.index} already downloaded."))
        case Some((_, MarkedForDownload)) =>
          Left(Pieces.OmgError(s"Piece ${blockRequest.index} still marked for download."))
        case Some((_, d @ Downloading(_, blocks))) =>
          val newState = d.copy(blocks = blocks + (blockRequest -> BlockState.Asked))
          Right(newState, updateState(blockRequest.index, newState))
          blocks.get(blockRequest) match {
            case Some(BlockState.Missing) =>
              val newState = d.copy(blocks = blocks + (blockRequest -> BlockState.Asked))
              Right(updateState(blockRequest.index, newState))
            case Some(BlockState.Asked) =>
              Left(Pieces.OmgError(s"Block $blockRequest already asked."))
            case Some(BlockState.WrittenToFile) =>
              Left(Pieces.OmgError(s"Block $blockRequest already written to file."))
            case None => Left(Pieces.OmgError(s"Block $blockRequest not found."))
          }
        case None => Left(Pieces.OmgError(s"Piece index ${blockRequest.index} not found."))
      }

    def markBlocksForDownload(blockRequests: List[BlockRequest]): Either[State.Pieces.OmgError, Pieces] =
      blockRequests match {
        case Nil => Right(this)
        case ::(head, tl) =>
          markBlockForDownload(head) match {
            case Left(value) => Left(value)
            case Right(value) => value.markBlocksForDownload(tl)
          }
      }

    def markPiecesForDownload(indexes: List[Int]): Either[State.Pieces.OmgError, Pieces] =
      indexes match {
        case Nil => Right(this)
        case ::(head, tl) =>
          markPieceForDownload(head) match {
            case Left(value) => Left(value)
            case Right(value) => value.markPiecesForDownload(tl)
          }
      }

    def markPieceForDownload(index: Int): Either[State.Pieces.OmgError, Pieces] =
      underlying.lift(index) match {
        case Some((_, Missing)) => Right(updateState(index, MarkedForDownload))
        case Some((_, _: Downloaded)) => Left(Pieces.OmgError(s"Piece $index already downloaded."))
        case Some((_, _: Downloading)) => Left(Pieces.OmgError(s"Piece $index already being downloaded."))
        case Some((_, MarkedForDownload)) => Left(Pieces.OmgError(s"Piece $index already marked for download."))
        case None => Left(Pieces.OmgError(s"Piece index $index not found."))
      }

    def maskAsMissing(blockRequest: BlockRequest): Either[Pieces.Error, Pieces] = {
      underlying.lift(blockRequest.index) match {
        case Some((_, Missing)) =>
          Left(Pieces.OmgError(s"Entire piece ${blockRequest.index} not yet registered."))
        case Some((_, _: Downloaded)) => Left(Pieces.OmgError(s"Piece ${blockRequest.index} already downloaded."))
        case Some((_, MarkedForDownload)) =>
          Left(Pieces.OmgError(s"Piece ${blockRequest.index} is marked for download."))
        case Some((_, d @ Downloading(_, blocks))) =>
          blocks.get(blockRequest) match {
            case Some(BlockState.Missing) =>
              Left(Pieces.OmgError(s"Block $blockRequest already set to missing."))
            case Some(BlockState.Asked) =>
              Right(updateState(blockRequest.index, d.copy(blocks = blocks + (blockRequest -> BlockState.Missing))))
            case Some(BlockState.WrittenToFile) =>
              Left(Pieces.OmgError(s"Block $blockRequest already written to file."))
            case None => Left(Pieces.OmgError(s"Block $blockRequest not found."))
          }
        case None => Left(Pieces.OmgError(s"Piece index ${blockRequest.index} not found."))
      }
    }

    def index(idx: Int): Option[PieceState] =
      underlying.lift(idx).map(_._2)

    /** Missing blocks of pieces that are being downloaded.
      *
      * Does not return missing blocks of pieces whose download hasn't started.
      *
      * @return Missing blocks of pieces that are being downloaded, alongside the associated file.
      */
    def missingBlocks: List[(PieceFile, BlockRequest)] =
      underlying.collect {
        case (_, Downloading(pieceFile, blocks)) =>
          blocks.collect { case (blockR, BlockState.Missing) => (pieceFile, blockR) }
      }.flatten

    def blockCompleted(block: BlockRequest): Either[Pieces.Error, (Option[Path], Pieces)] = {
      underlying.lift(block.index) match {
        case Some((_, Missing)) => Left(Pieces.OmgError(s"Piece index ${block.index} set to state missing."))
        case Some((_, downloading @ Downloading(pieceFile, blocks))) =>
          blocks.get(block) match {
            case Some(BlockState.Missing) => Left(Pieces.OmgError(s"Block '$block' set to missing."))
            case Some(BlockState.Asked) =>
              val isLastBlock = blocks.count {
                case (_, BlockState.WrittenToFile) => true
                case _ => false
              } == blocks.size - 1

              if (isLastBlock) {
                val newState =
                  updateState(block.index, Downloaded(pieceFile.path))
                Right(Some(pieceFile.path), newState)
              } else {
                val newState =
                  updateState(block.index, downloading.copy(blocks = blocks + (block -> BlockState.WrittenToFile)))
                Right(None -> newState)
              }

            case Some(BlockState.WrittenToFile) => Left(Pieces.OmgError(s"Block '$block' already written to file"))
            case None => Left(Pieces.OmgError(s"Block $block not found."))
          }
        case Some((_, Downloaded(_))) => Left(Pieces.OmgError(s"Piece index ${block.index} already downloaded."))
        case Some((_, MarkedForDownload)) => Left(Pieces.OmgError(s"Piece index ${block.index} marked for download."))
        case None => Left(Pieces.OmgError(s"Piece index ${block.index} not found."))
      }
    }
  }

  object Pieces {
    sealed trait Error
    case class OmgError(msg: String) extends Error

    def from(pieces: NonEmptyList[PieceHash]): Pieces = Pieces(pieces.toList.map(hash => hash -> Missing))
  }

  sealed trait PieceState extends Product with Serializable

  object PieceState {

    case class Downloading(
        file: PieceFile,
        blocks: Map[BlockRequest, BlockState]
    ) extends PieceState

    case class Downloaded(location: Path) extends PieceState
    case object Missing extends PieceState
    case object MarkedForDownload extends PieceState

    sealed trait BlockState

    object BlockState {
      case object Missing extends BlockState
      case object Asked extends BlockState
      case object WrittenToFile extends BlockState
    }
  }

}
