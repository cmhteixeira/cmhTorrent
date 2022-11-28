package com.cmhteixeira.bittorrent.peerprotocol

import java.io.Serializable

trait Peer {
  def getState: Peer.State
}

object Peer {

  sealed trait State extends Product with Serializable {
    def me: ConnectionState
    def peer: ConnectionState
  }

  final case object Begin extends State {
    override val me: ConnectionState = ChokeAndUnInterested
    override val peer: ConnectionState = ChokeAndUnInterested
  }

  sealed trait Terminal extends State
  case class TcpIssue(msg: String, me: ConnectionState, peer: ConnectionState) extends Terminal

  case class ErrorHandshake(msg: String) extends Terminal {
    override val me: ConnectionState = ChokeAndUnInterested
    override val peer: ConnectionState = ChokeAndUnInterested
  }

  case class OtherError(msg: String, me: ConnectionState, peer: ConnectionState) extends Terminal

  case class Handshaked(
      reservedBytes: Long,
      peerId: String,
      protocol: String,
      me: ConnectionState,
      peer: ConnectionState
  ) extends State

  sealed trait ConnectionState
  case object ChokeAndInterested extends ConnectionState
  case object ChokeAndUnInterested extends ConnectionState
  case object UnChokedAndInterested extends ConnectionState
  case object UnChokedAndUnInterested extends ConnectionState

  case class Config(tcpConnectTimeoutMillis: Int, myPeerId: String)
}
