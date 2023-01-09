package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import com.cmhteixeira.bittorrent.swarm.RequestBlocks.{Configuration, randomAccessFileOpeningMode}
import com.cmhteixeira.bittorrent.swarm.State.BlockState.Asked
import com.cmhteixeira.bittorrent.swarm.State.{Active, Downloading, PeerState, Pieces}
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector

import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}

private[swarm] class RequestBlocks private (
    peers: AtomicReference[Map[InetSocketAddress, PeerState]],
    pieces: AtomicReference[Pieces],
    writerThread: WriterThread,
    torrent: Torrent,
    randomGen: Random,
    config: Configuration,
    mainExecutor: ExecutionContext
) extends Runnable {
  private val logger = LoggerFactory.getLogger("Swarm")

  override def run(): Unit =
    Try {
      logger.info("Updating pieces ...")
      updatePiece()
    } match {
      case Failure(exception) => logger.error("Updating pieces ...", exception)
      case Success(_) => logger.info("Completed updating pieces.")
    }

  private def updatePiece(): Unit = {
    val currentPeers = peers.get()
    val currentState = pieces.get()
    val downloadingPieces = currentState.countDownloading
    if (downloadingPieces >= 5) logger.info(s"Downloading $downloadingPieces pieces. Waiting until some finish.")
    else {
      val numPiecesToDownload = 5 - downloadingPieces
      val piecesToDownload = currentState.missingPieces.take(numPiecesToDownload)
      piecesToDownload
        .flatMap { pieceIndex =>
          val peersHave = currentPeers.collect { case (_, Active(peer)) if peer.hasPiece(pieceIndex) => peer }.toList
          if (peersHave.isEmpty) None
          else peersHave.lift(randomGen.nextInt(peersHave.size)).map(pieceIndex -> _)
        }
        .foreach {
          case (idx, peer) =>
            val blockRequestResponses = torrent
              .splitInBlocks(idx, config.blockSize)
              .map { case (offset, len) => BlockRequest(idx, offset, len) }
              .map(blockR => blockR -> peer.download(blockR))

            monkey(idx, blockRequestResponses)
        }
    }
  }

  private def monkey(pieceIndex: Int, in: List[(BlockRequest, Future[ByteVector])]): Unit = {
    val currentState = pieces.get()
    val filePath = fileName(pieceIndex)
    val backingFile = new RandomAccessFile(filePath.toFile, randomAccessFileOpeningMode)
    val sizeFile = torrent.pieceSize(pieceIndex)
    logger.info(s"Creating file '${filePath.toAbsolutePath}' for piece $pieceIndex with $sizeFile bytes.")
    backingFile.write(new Array[Byte](sizeFile))
    val downloading = Downloading(backingFile, in.map { case (blockRequest, _) => blockRequest -> Asked }.toMap)
    val newState = currentState.updateState(pieceIndex, downloading)
    if (!pieces.compareAndSet(currentState, newState)) monkey(pieceIndex, in)
    else {
      in.foreach {
        case (BlockRequest(index, offSet, _), eventualBlock) =>
          eventualBlock.onComplete {
            case Failure(exception) => logger.error("OMGGGGG....", exception)
            case Success(pieceBlock) =>
              writerThread.add(WriterThread.Message(index, offSet, backingFile, pieceBlock))
          }(mainExecutor)
      }
    }
  }

  private def fileName(pieceIndex: Int): Path =
    config.downloadDir.resolve(
      s"cmhTorrent-${torrent.infoHash.hex}.piece-$pieceIndex-of-${torrent.info.pieces.size - 1}"
    )
}

private[swarm] object RequestBlocks {
  private val randomAccessFileOpeningMode = "rws"

  case class Configuration(downloadDir: Path, blockSize: Int)

  def apply(
      peers: AtomicReference[Map[InetSocketAddress, PeerState]],
      pieces: AtomicReference[Pieces],
      writerThread: WriterThread,
      torrent: Torrent,
      random: Random,
      config: Configuration,
      mainExecutor: ExecutionContext
  ): RequestBlocks = new RequestBlocks(peers, pieces, writerThread, torrent, random, config, mainExecutor)
}
