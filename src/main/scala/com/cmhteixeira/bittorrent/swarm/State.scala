package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.peerprotocol.Peer
import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import com.cmhteixeira.cmhtorrent.PieceHash

import java.io.RandomAccessFile
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
  }

  object Pieces {

    def from(pieces: NonEmptyList[PieceHash]): Pieces = Pieces(pieces.toList.map(hash => hash -> Missing))
  }

  sealed trait PieceState extends Product with Serializable

  case class Downloading(
      file: RandomAccessFile,
      //      whenAllFinished: Promise[Path],
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
