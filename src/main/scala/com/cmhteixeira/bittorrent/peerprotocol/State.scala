package com.cmhteixeira.bittorrent.peerprotocol

import java.io.Serializable

private[peerprotocol] sealed trait State extends Product with Serializable

private[peerprotocol] object State {

  def begin = Begin

  case object TcpConnected extends State {
    def handshakeError(msg: String): HandshakeError = HandshakeError(msg)

    def handShaked(reservedBytes: Long, peerId: String, protocol: String): Handshaked =
      Handshaked(
        reservedBytes,
        peerId,
        protocol,
        ConnectionState(isChocked = true, isInterested = false),
        ConnectionState(isChocked = true, isInterested = false),
        List.empty
      )
  }

  case object Begin extends State {
    def connectedError(msg: String): HandshakeError = HandshakeError(msg)

    def connected = TcpConnected
  }

  case class HandshakeError(msg: String) extends State

  case class TerminalError(
      reservedBytes: Long,
      peerId: String,
      protocol: String,
      me: ConnectionState,
      peer: ConnectionState,
      msg: String
  ) extends State

  case class Handshaked(
      reservedBytes: Long,
      peerId: String,
      protocol: String,
      me: ConnectionState,
      peer: ConnectionState,
      peerPieces: List[(String, Boolean)]
  ) extends State {
    def chokeMe: Handshaked = copy(me = me.choke)
    def chokePeer: Handshaked = copy(peer = peer.choke)
    def unShokeMe: Handshaked = copy(me = me.unChoke)
    def unShokePeer: Handshaked = copy(peer = peer.unChoke)
    def meInterested: Handshaked = copy(me = me.interested)
    def peerInterested: Handshaked = copy(peer = peer.interested)
    def meNotIntested: Handshaked = copy(me = me.notInterested)
    def peerNotInterested: Handshaked = copy(peer = peer.notInterested)
    def error(msg: String): TerminalError = TerminalError(reservedBytes, peerId, protocol, me, peer, msg)

    def peerPieces(
        peerPieces: List[(String, Boolean)]
    ): Handshaked = copy(peerPieces = peerPieces)

    def addPeerPiece(index: Int): Handshaked = {
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
