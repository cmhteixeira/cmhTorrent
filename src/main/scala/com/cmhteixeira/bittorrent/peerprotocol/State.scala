package com.cmhteixeira.bittorrent.peerprotocol

import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import com.cmhteixeira.bittorrent.peerprotocol.State.BlockState.{Received, Sent}
import com.cmhteixeira.bittorrent.peerprotocol.State.TerminalError.HandshakeError
import scodec.bits.{BitVector, ByteVector}

import java.io.Serializable
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
    case class SendingHaveOrAmInterestedMessage(error: Throwable) extends Error
    case class WritingPieceToFile(pieceIndex: Int, error: Throwable) extends Error
  }

  def begin = Begin

  case object TcpConnected extends Good {

    def handShaked(reservedBytes: Long, peerId: String, protocol: String, numPieces: Int): Handshaked =
      Handshaked(
        reservedBytes,
        peerId,
        protocol,
        MyState(ConnectionState(isChocked = true, isInterested = false), Map.empty),
        PeerState(ConnectionState(isChocked = true, isInterested = false), BitVector.fill(numPieces)(high = false))
      )
  }

  case object Begin extends Good {

    def connected = TcpConnected

  }

  case class Handshaked(
      reservedBytes: Long,
      peerId: String,
      protocol: String,
      me: MyState,
      peer: PeerState
  ) extends Good {
    def chokeMe: Handshaked = copy(me = me.choke)
    def chokePeer: Handshaked = copy(peer = peer.choke)
    def unShokeMe: Handshaked = copy(me = me.unChoke)
    def unShokePeer: Handshaked = copy(peer = peer.unChoke)
    def meInterested: Handshaked = copy(me = me.interested)
    def peerInterested: Handshaked = copy(peer = peer.interested)
    def meNotIntested: Handshaked = copy(me = me.notInterested)
    def peerNotInterested: Handshaked = copy(peer = peer.notInterested)

    def peerPieces(bitField: List[Boolean]): Handshaked = copy(peer = peer.hasPieces(bitField))

    def pierHasPiece(index: Int): Handshaked = copy(peer = peer.hasPiece(index))

    def download(block: BlockRequest, channel: Promise[ByteVector]): Handshaked =
      copy(me =
        MyState(
          connectionState = me.connectionState.copy(isInterested = true),
          requests = me.requests + (block -> Sent(channel))
        )
      )

    def received(blockRequest: BlockRequest): Handshaked =
      copy(me = me.copy(requests = me.requests + (blockRequest -> Received)))
  }

  case class MyState(connectionState: ConnectionState, requests: Map[BlockRequest, BlockState]) {
    def choke: MyState = copy(connectionState = connectionState.choke)
    def unChoke: MyState = copy(connectionState = connectionState.unChoke)
    def interested: MyState = copy(connectionState = connectionState.interested)
    def notInterested: MyState = copy(connectionState = connectionState.notInterested)
  }

  case class PeerState(connectionState: ConnectionState, piecesBitField: BitVector) {
    def choke: PeerState = copy(connectionState = connectionState.choke)
    def unChoke: PeerState = copy(connectionState = connectionState.unChoke)
    def interested: PeerState = copy(connectionState = connectionState.interested)
    def notInterested: PeerState = copy(connectionState = connectionState.notInterested)
    def hasPiece(index: Int): PeerState = copy(piecesBitField = piecesBitField.set(index))
    def hasPieces(indexes: List[Boolean]): PeerState = copy(piecesBitField = BitVector.bits(indexes))
  }

  sealed trait BlockState

  object BlockState {
    case class Sent(channel: Promise[ByteVector]) extends BlockState
    case object Received extends BlockState
  }

  case class ConnectionState(isChocked: Boolean, isInterested: Boolean) {
    def choke = copy(isChocked = true)
    def unChoke = copy(isChocked = false)
    def interested = copy(isInterested = true)
    def notInterested = copy(isInterested = false)
  }

}
