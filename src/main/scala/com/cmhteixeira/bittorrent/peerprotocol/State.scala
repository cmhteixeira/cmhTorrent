package com.cmhteixeira.bittorrent.peerprotocol

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.peerprotocol.State.MyPieceState.NeitherHaveOrWant
import com.cmhteixeira.bittorrent.peerprotocol.State.TerminalError.HandshakeError
import scodec.bits.ByteVector

import java.io.Serializable
import java.nio.file.Path
import scala.concurrent.Promise

private[peerprotocol] sealed trait State extends Product with Serializable

private[peerprotocol] object State {

  sealed trait Good extends State {
    def error(error: TerminalError.Error): TerminalError = TerminalError(this, error)
    def handshakeError(msg: String): TerminalError = TerminalError(this, HandshakeError(msg))
  }

  case class TerminalError(
      previousState: Good,
      error: TerminalError.Error
  ) extends State

  object TerminalError {
    sealed trait Error
    case class UnexpectedMsgSize(msgType: String, expected: Int, actual: Int) extends Error
    case class ReadBadNumberBytes(expected: Int, actual: Int) extends Error
    case class ReceivingMsg(error: Throwable) extends Error
    case class HandshakeError(msg: String) extends Error
    case class MsgTypeNotRecognized(msgType: Int) extends Error
    case class TcpConnection(error: Throwable) extends Error
    case class SendingHandshake(error: Throwable) extends Error

    case class WritingPieceToFile(pieceIndex: Int, error: Throwable) extends Error
  }

  def begin = Begin

  case object TcpConnected extends Good {

    def handShaked(reservedBytes: Long, peerId: String, protocol: String, numPieces: Int): Handshaked =
      Handshaked(
        reservedBytes,
        peerId,
        protocol,
        ConnectionState(isChocked = true, isInterested = false),
        ConnectionState(isChocked = true, isInterested = false),
        Pieces((1 to numPieces).toList.map(_ => PieceState(NeitherHaveOrWant, PeerPieceState(false, false))))
      )
  }

  case object Begin extends Good {

    def connected = TcpConnected

  }

  case class Handshaked(
      reservedBytes: Long,
      peerId: String,
      protocol: String,
      me: ConnectionState,
      peer: ConnectionState,
      pieces: Pieces
  ) extends Good {
    def chokeMe: Handshaked = copy(me = me.choke)
    def chokePeer: Handshaked = copy(peer = peer.choke)
    def unShokeMe: Handshaked = copy(me = me.unChoke)
    def unShokePeer: Handshaked = copy(peer = peer.unChoke)
    def meInterested: Handshaked = copy(me = me.interested)
    def peerInterested: Handshaked = copy(peer = peer.interested)
    def meNotIntested: Handshaked = copy(me = me.notInterested)
    def peerNotInterested: Handshaked = copy(peer = peer.notInterested)

    def peerPieces(bitField: List[Boolean]): Handshaked = copy(pieces = pieces.pierHasPieces(bitField))

    def pierHasPiece(index: Int): Handshaked = copy(pieces = pieces.pierHasPiece(index))

    def updateMyState(i: Int, state: MyPieceState): Handshaked = copy(pieces = pieces.updateMyState(i, state))

    def updateMyState(i: Int, state: MyPieceState, me: ConnectionState): Handshaked =
      copy(pieces = pieces.updateMyState(i, state), me = me)

    def numberAsked: Int =
      pieces.underlying.count(_.me match {
        case MyPieceState.Asked(_) => true
        case _ => false
      })
  }

  case class Pieces(underlying: List[PieceState]) {

    def size: Int = underlying.size

    def pierHasPieces(bitField: List[Boolean]): Pieces =
      Pieces(
        underlying.zip(bitField).map {
          case (pieceState @ PieceState(_, PeerPieceState(_, peerWants)), peerHas) =>
            pieceState.copy(peer = PeerPieceState(peerHas, peerWants))
        }
      )

    def pierHasPiece(index: Int): Pieces =
      Pieces(underlying.zipWithIndex.map {
        case (pieceState @ PieceState(_, PeerPieceState(_, wants)), thisIndex) if thisIndex == index =>
          pieceState.copy(peer = PeerPieceState(true, wants))
        case (pair, _) => pair
      })

    def get(i: Int): Option[PieceState] = underlying.lift(i)

    def update(i: Int, state: PieceState): Pieces =
      Pieces(underlying.zipWithIndex.map {
        case (_, index) if index == i => state
        case (state, _) => state
      })

    def updateMyState(i: Int, state: MyPieceState): Pieces =
      Pieces(underlying.zipWithIndex.map {
        case (PieceState(_, peerState), index) if index == i => PieceState(state, peerState)
        case (state, _) => state
      })

  }

  case class PieceState(me: MyPieceState, peer: PeerPieceState)

  case class PeerPieceState(has: Boolean, wants: Boolean)

  sealed trait MyPieceState extends Product with Serializable

  object MyPieceState {

    case class Want(channel: Promise[Path]) extends MyPieceState

    case class Asked(channel: Promise[Path]) extends MyPieceState {

      def firstBlock(byteOffsSet: Int, data: ByteVector): Downloading =
        Downloading(channel, Blocks.one(byteOffsSet, data))
    }

    case class Downloading(channel: Promise[Path], blocks: Blocks) extends MyPieceState {

      def anotherBlock(byteOffsSet: Int, data: ByteVector): Downloading =
        Downloading(channel, blocks.append(byteOffsSet, data))
    }
    case class Have(path: Path) extends MyPieceState
    case object NeitherHaveOrWant extends MyPieceState

    case class Blocks(i: NonEmptyList[Block]) {
      def append(byteOffset: Int, data: ByteVector): Blocks = Blocks(i :+ Block(byteOffset, data))

      // todo: should we assume blocks are mutually exclusive with respect to byte offsets?
      def assemble(pieceSize: Int): Option[ByteVector] = {
        def internal(nextOffSet: Int, acc: ByteVector): Option[ByteVector] = {
          if (nextOffSet == pieceSize) Some(acc)
          else
            i.toList.collectFirst { case Block(offset, data) if offset == nextOffSet => data } match {
              case Some(value) => internal(nextOffSet + value.size.toInt, acc ++ value)
              case None => None
            }
        }

        internal(0, ByteVector.empty)
      }
    }

    object Blocks {
      def one(offSet: Int, data: ByteVector): Blocks = Blocks(NonEmptyList.one(Block(offSet, data)))
    }

    case class Block(offSet: Int, data: ByteVector)

  }

  case class ConnectionState(isChocked: Boolean, isInterested: Boolean) {
    def choke = copy(isChocked = true)
    def unChoke = copy(isChocked = false)
    def interested = copy(isInterested = true)
    def notInterested = copy(isInterested = false)
  }

}
