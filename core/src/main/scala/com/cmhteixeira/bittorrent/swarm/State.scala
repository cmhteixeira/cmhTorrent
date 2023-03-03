package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.peerprotocol.Peer
import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import com.cmhteixeira.bittorrent.swarm.State.PieceState._
import com.cmhteixeira.cmhtorrent.PieceHash

private[swarm] object State {
  sealed trait PeerState
  case class Tried(triedLast: Long) extends PeerState
  case class Active(peer: Peer) extends PeerState

  case class Pieces(underlying: List[(PieceHash, PieceState)]) {

    def updateState(pieceIndex: Int, newState: PieceState): Pieces =
      Pieces(underlying.zipWithIndex.map {
        case ((hash, _), i) if i == pieceIndex => hash -> newState
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
        case (acc, (_, Downloading(blocks))) =>
          acc + blocks.count {
            case (_, BlockState.Asked) => true
            case _ => false
          }
        case (acc, _) => acc
      }

    def pieceHashVerified(index: Int): Either[String, Pieces] = {
      underlying.lift(index) match {
        case Some((_, State.PieceState.Downloaded)) => Right(updateState(index, State.PieceState.DownloadedAndVerified))
        case Some((_, State.PieceState.Missing)) => Left("Missing")
        case Some((_, State.PieceState.Downloading(_))) => Left("Downloading")
        case Some((_, State.PieceState.DownloadedAndVerified)) => Left("Already verified.")
        case None => Left(s"Piece $index does not exist.")
      }
    }

    def markBlockForDownload(blockRequest: BlockRequest) =
      underlying.lift(blockRequest.index) match {
        case Some((_, DownloadedAndVerified)) =>
          Left(Pieces.OmgError(s"Piece ${blockRequest.index} already downloaded and verified."))
        case Some((_, Missing)) =>
          Left(Pieces.OmgError(s"Entire piece ${blockRequest.index} not yet registered."))
        case Some((_, Downloaded)) => Left(Pieces.OmgError(s"Piece ${blockRequest.index} already downloaded."))
        case Some((_, d @ Downloading(blocks))) =>
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

    def markPiecesForDownload(
        indexes: List[(Int, List[(BlockRequest, Boolean)])]
    ): Either[State.Pieces.OmgError, Pieces] =
      indexes match {
        case Nil => Right(this)
        case ::((pieceIndex, pieceBlocks), tl) =>
          markPieceForDownload(pieceIndex, pieceBlocks) match {
            case Left(value) => Left(value)
            case Right(value) => value.markPiecesForDownload(tl)
          }
      }

    def markPieceForDownload(index: Int, blocks: List[(BlockRequest, Boolean)]): Either[State.Pieces.OmgError, Pieces] =
      underlying.lift(index) match {
        case Some((_, Missing)) =>
          Right(
            updateState(
              index,
              Downloading(blocks.map {
                case (request, asked) if asked => request -> BlockState.Asked
                case (request, asked) if !asked => request -> BlockState.Missing
              }.toMap)
            )
          )
        case Some((_, Downloaded)) => Left(Pieces.OmgError(s"Piece $index already downloaded."))
        case Some((_, DownloadedAndVerified)) => Left(Pieces.OmgError(s"Piece $index already downloaded and verified."))
        case Some((_, _: Downloading)) => Left(Pieces.OmgError(s"Piece $index already being downloaded."))
        case None => Left(Pieces.OmgError(s"Piece index $index not found."))
      }

    def maskAsMissing(blockRequest: BlockRequest): Either[Pieces.Error, Pieces] = {
      underlying.lift(blockRequest.index) match {
        case Some((_, Missing)) =>
          Left(Pieces.OmgError(s"Entire piece ${blockRequest.index} not yet registered."))
        case Some((_, Downloaded)) => Left(Pieces.OmgError(s"Piece ${blockRequest.index} already downloaded."))
        case Some((_, DownloadedAndVerified)) =>
          Left(Pieces.OmgError(s"Piece ${blockRequest.index} already downloaded and verified."))
        case Some((_, d @ Downloading(blocks))) =>
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

    def index(idx: Int): Option[(PieceHash, PieceState)] =
      underlying.lift(idx)

    /** Missing blocks of pieces that are being downloaded.
      *
      * Does not return missing blocks of pieces whose download hasn't started.
      *
      * @return Missing blocks of pieces that are being downloaded, alongside the associated file.
      */
    def missingBlocks: List[BlockRequest] =
      underlying.collect {
        case (_, Downloading(blocks)) =>
          blocks.collect { case (blockR, BlockState.Missing) => blockR }
      }.flatten

    def blockCompleted(block: BlockRequest): Either[Pieces.Error, Pieces] = {
      underlying.lift(block.index) match {
        case Some((_, Missing)) => Left(Pieces.OmgError(s"Piece index ${block.index} set to state missing."))
        case Some((_, downloading @ Downloading(blocks))) =>
          blocks.get(block) match {
            case Some(BlockState.Missing) => Left(Pieces.OmgError(s"Block '$block' set to missing."))
            case Some(BlockState.Asked) =>
              val isLastBlock = blocks.count {
                case (_, BlockState.WrittenToFile) => true
                case _ => false
              } == blocks.size - 1

              if (isLastBlock)
                Right(updateState(block.index, Downloaded))
              else
                Right(updateState(block.index, downloading.copy(blocks = blocks + (block -> BlockState.WrittenToFile))))

            case Some(BlockState.WrittenToFile) => Left(Pieces.OmgError(s"Block '$block' already written to file"))
            case None => Left(Pieces.OmgError(s"Block $block not found."))
          }
        case Some((_, Downloaded)) => Left(Pieces.OmgError(s"Piece index ${block.index} already downloaded."))
        case Some((_, DownloadedAndVerified)) => Left(Pieces.OmgError(s"Piece index ${block.index} already downloaded and verified."))
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

    case class Downloading(blocks: Map[BlockRequest, BlockState]) extends PieceState
    case object Downloaded extends PieceState
    case object DownloadedAndVerified extends PieceState
    case object Missing extends PieceState

    sealed trait BlockState

    object BlockState {
      case object Missing extends BlockState
      case object Asked extends BlockState
      case object WrittenToFile extends BlockState
    }
  }

}
