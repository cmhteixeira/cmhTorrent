package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import org.scalatest.{FunSuite, Matchers}
import com.cmhteixeira.cmhtorrent.{Torrent => OriginalTorrent}
import com.cmhteixeira.bencode.parse
import com.cmhteixeira.bencode.{Decoder => BDecoder}
import com.cmhteixeira.bittorrent.InfoHash
import com.cmhteixeira.bittorrent.swarm.Torrent.FileChunk
import scodec.bits.ByteVector

class TorrentSpec extends FunSuite with Matchers {
  val decoder = implicitly[BDecoder[OriginalTorrent]]

  lazy val torrent1 = for {
    parsed <- parse(getClass.getResourceAsStream("/clonezillaTorrent.torrent").readAllBytes()).left.map(_.toString)
    torrent <- decoder(parsed).left.map(_.toString)
    info <- parsed.asDict.flatMap(_.apply("info")).toRight("Could not extract valid 'info' from Bencode.")
    swarmTorrent <- Torrent(InfoHash(info), torrent)
  } yield swarmTorrent

  lazy val torrent2 = for {
    parsed <- parse(
      getClass
        .getResourceAsStream("/MagnetLinkToTorrent_99B32BCD38B9FBD8E8B40D2B693CF905D71ED97F.torrent")
        .readAllBytes()
    ).left.map(_.toString)
    torrent <- decoder(parsed).left.map(_.toString)
    info <- parsed.asDict.flatMap(_.apply("info")).toRight("Could not extract valid 'info' from Bencode.")
    swarmTorrent <- Torrent(InfoHash(info), torrent)
  } yield swarmTorrent

  test("verify assertions for torrent1") {
    torrent1 match {
      case Left(value) => fail(s"Tested not attempted: '$value'.")
      case Right(swarmTorrent) =>
        val block = ByteVector.low(10)
        val firstFile = swarmTorrent.info match {
          case Torrent.SingleFile(_, path, _, _) => path
          case Torrent.MultiFile(files, name, _, _) => name.resolve(files.head.path)
        }

        swarmTorrent.fileChunks(0, 0, block) shouldBe Some(NonEmptyList.one(FileChunk(firstFile, 0, block)))
        swarmTorrent.fileChunks(1800, 0, block) shouldBe None
        swarmTorrent.fileChunks(1696, 0, block) shouldBe None
        swarmTorrent.fileChunks(1695, 0, block) shouldBe Some(
          NonEmptyList.one(FileChunk(firstFile, (1695 * swarmTorrent.info.pieceLength).toInt, block))
        )
        swarmTorrent.fileChunks(1695, 40, block) shouldBe Some(
          NonEmptyList.one(FileChunk(firstFile, (1695 * swarmTorrent.info.pieceLength + 40).toInt, block))
        )
    }
  }

  test("verify assertions for torrent2") {
    torrent2 match {
      case Left(value) => fail(s"Tested not attempted: '$value'.")
      case Right(swarmTorrent) =>
        val files = swarmTorrent.info match {
          case Torrent.SingleFile(_, path, _, _) => List(path)
          case Torrent.MultiFile(files, name, _, _) => files.map(_.path).map(name.resolve).toList
        }
        val block1 = ByteVector.low(10)
        val block2 = ByteVector.low(200)

        swarmTorrent.fileChunks(0, 0, block1) shouldBe Some(NonEmptyList.one(FileChunk(files.head, 0, block1)))
        swarmTorrent.fileChunks(0, 0, block2) shouldBe Some(
          NonEmptyList.of(
            FileChunk(files.head, 0, block2.take(175)),
            FileChunk(files.tail.head, 0, block2.drop(175))
          )
        )

        val block3 = ByteVector.low(262144)
        swarmTorrent.fileChunks(1595, 262100, block3) shouldBe None

        val block4 = ByteVector.low(108839 - 25)
        println(swarmTorrent.pieceSize(1595))
        swarmTorrent.fileChunks(1595, 10, block4) shouldBe
          Some(
            NonEmptyList.of( // revisit this.
              FileChunk(files(1), 418119505 + 10, block4.take(418227626 - (418119505 + 10))),
              FileChunk(files(2), 0, block4.drop(418227626 - (418119505 + 10)))
            )
          )
    }
  }
}
