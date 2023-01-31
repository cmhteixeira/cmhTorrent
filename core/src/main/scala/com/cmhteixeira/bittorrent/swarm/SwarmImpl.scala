package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.peerprotocol.Peer
import com.cmhteixeira.bittorrent.swarm.State.{BlockState, PeerState, Pieces}
import com.cmhteixeira.bittorrent.swarm.Swarm.Tried
import com.cmhteixeira.bittorrent.tracker.Tracker

import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{LinkedBlockingQueue, ScheduledExecutorService, TimeUnit}
import scala.concurrent.ExecutionContext
import scala.util.Random

private[bittorrent] class SwarmImpl private (
    thePeers: AtomicReference[Map[InetSocketAddress, PeerState]],
    thePieces: AtomicReference[State.Pieces],
    torrent: Torrent,
    tracker: Tracker,
    scheduler: ScheduledExecutorService,
    upsertPeers: UpsertPeers,
    requestBlocks: RequestBlocks
) extends Swarm {
  tracker.submit(torrent.toTrackerTorrent)
  scheduler.scheduleAtFixedRate(upsertPeers, 0, 60, TimeUnit.SECONDS)
  scheduler.scheduleAtFixedRate(requestBlocks, 0, 20, TimeUnit.SECONDS)

  override def peers: Map[InetSocketAddress, Swarm.PeerState] =
    thePeers.get().map {
      case (peerSocket, State.Tried(triedLast)) => peerSocket -> Tried(triedLast)
      case (peerSocket, State.Active(peer)) => peerSocket -> Swarm.On(peer.getState)
    }

  override def pieces: List[Swarm.PieceState] =
    thePieces.get().underlying.map {
      case (_, State.Downloading(_, blocks)) =>
        Swarm.Downloading(
          blocks.size,
          blocks.count {
            case (_, BlockState.WrittenToFile) => true
            case _ => false
          }
        )
      case (_, State.Downloaded(path)) => Swarm.Downloaded(path)
      case (_, State.Missing) => Swarm.Missing
    }
  override def close: Unit = println("Closing this and that")
}

object SwarmImpl {

  type PeerFactory = InetSocketAddress => Peer

  def apply(
      tracker: Tracker,
      mainExecutor: ExecutionContext,
      scheduler: ScheduledExecutorService,
      peerFactory: PeerFactory,
      random: Random,
      downloadDir: Path,
      blockSize: Int,
      torrent: Torrent
  ): SwarmImpl = {
    val peers = new AtomicReference[Map[InetSocketAddress, PeerState]](Map.empty)
    val pieces = new AtomicReference[State.Pieces](Pieces.from(torrent.info.pieces))
    val writerThread = WriterThread(mainExecutor, new LinkedBlockingQueue[WriterThread.Message]())

    new SwarmImpl(
      peers,
      pieces,
      torrent,
      tracker,
      scheduler,
      UpsertPeers(peers, peerFactory, torrent, tracker),
      RequestBlocks(
        peers,
        pieces,
        writerThread,
        torrent,
        random,
        RequestBlocks.Configuration(downloadDir, blockSize),
        mainExecutor,
        scheduler
      )
    )
  }
}
