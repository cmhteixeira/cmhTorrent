package com.cmhteixeira.bittorrent.peerprotocol

import java.io.Serializable

sealed trait State extends Product with Serializable

object State {

  def begin: Begin = BeginImpl

  sealed trait TcpConnected extends State {
    def handshakeError(msg: String): HandshakeError = HandshakeErrorImpl(msg)

    def handShaked(reservedBytes: Long, peerId: String, protocol: String): Handshaked =
      HandshakedImpl(
        reservedBytes,
        peerId,
        protocol,
        ConnectionState(isChocked = true, isInterested = false),
        ConnectionState(isChocked = true, isInterested = false),
        List.empty
      )
  }

  private case object TcpConnectedImpl extends TcpConnected

  sealed trait Begin extends State {
    def connectedError(msg: String): HandshakeError = HandshakeErrorImpl(msg)

    def connected: TcpConnected = TcpConnectedImpl
  }

  private case object BeginImpl extends Begin

  sealed trait HandshakeError extends State {
    def msg: String
  }

  sealed trait TerminalError extends State {
    def reservedBytes: Long
    def peerId: String
    def protocol: String
    def me: ConnectionState
    def peer: ConnectionState

    def msg: String
  }
  private case class HandshakeErrorImpl(msg: String) extends HandshakeError

  private case class TerminalErrorImpl(
      reservedBytes: Long,
      peerId: String,
      protocol: String,
      me: ConnectionState,
      peer: ConnectionState,
      msg: String
  ) extends TerminalError

  sealed trait Handshaked extends State {
    def reservedBytes: Long
    def peerId: String
    def protocol: String
    def me: ConnectionState
    def peer: ConnectionState
    def error(msg: String): TerminalError = TerminalErrorImpl(reservedBytes, peerId, protocol, me, peer, msg)

    def peerPieces: List[(String, Boolean)]

    def chokeMe: Handshaked
    def chokePeer: Handshaked

    def unShokeMe: Handshaked

    def unShokePeer: Handshaked

    def meInterested: Handshaked

    def meNotIntested: Handshaked
    def peerInterested: Handshaked

    def peerNotInterested: Handshaked

    def peerPieces(peerPieces: List[(String, Boolean)]): Handshaked

    def addPeerPiece(index: Int): Handshaked
  }

  private case class HandshakedImpl(
      reservedBytes: Long,
      peerId: String,
      protocol: String,
      me: ConnectionState,
      peer: ConnectionState,
      peerPieces: List[(String, Boolean)]
  ) extends Handshaked {
    override def chokeMe: Handshaked = copy(me = me.choke)
    override def chokePeer: Handshaked = copy(peer = peer.choke)
    override def unShokeMe: Handshaked = copy(me = me.unChoke)
    override def unShokePeer: Handshaked = copy(peer = peer.unChoke)
    override def meInterested: Handshaked = copy(me = me.interested)
    override def peerInterested: Handshaked = copy(peer = peer.interested)
    override def meNotIntested: Handshaked = copy(me = me.notInterested)
    override def peerNotInterested: Handshaked = copy(peer = peer.notInterested)

    override def peerPieces(
        peerPieces: List[(String, Boolean)]
    ): Handshaked = copy(peerPieces = peerPieces)

    override def addPeerPiece(index: Int): Handshaked = {
      val newPieces = peerPieces.zipWithIndex.map {
        case ((hash, set), i) if i == index => (hash, true)
        case (pair, _) => pair
      }

      copy(peerPieces = newPieces)
    }
  }

  case class ConnectionState(isChocked: Boolean, isInterested: Boolean) {
    def choke = copy(isChocked = true)
    def unChoke = copy(isChocked = false)
    def interested = copy(isInterested = true)
    def notInterested = copy(isInterested = false)
  }

}
