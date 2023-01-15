package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.peerprotocol.Peer
import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
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

    def countDownloading: Int =
      underlying.count {
        case (_, _: Downloading) => true
        case _ => false
      }

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

  case class Downloading(
      file: PieceFile,
      blocks: Map[BlockRequest, BlockState]
  ) extends PieceState

  sealed trait BlockState

  object BlockState {
    case object Missing extends BlockState
    case object Asked extends BlockState
    case object WrittenToFile extends BlockState
  }

  case class Downloaded(location: Path) extends PieceState
  case object Missing extends PieceState
}
