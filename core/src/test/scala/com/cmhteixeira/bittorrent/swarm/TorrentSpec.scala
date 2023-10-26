package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.Torrent
import com.cmhteixeira.bittorrent.Torrent.FileChunk
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector

class TorrentSpec extends AnyFunSuite with Matchers {

  lazy val torrent1 =
    Torrent(getClass.getResourceAsStream("/clonezillaTorrent.torrent").readAllBytes())

  lazy val torrent2 =
    Torrent(
      getClass
        .getResourceAsStream("/MagnetLinkToTorrent_99B32BCD38B9FBD8E8B40D2B693CF905D71ED97F.torrent")
        .readAllBytes()
    )

  lazy val torrent3 = Torrent(
    getClass
      .getResourceAsStream("/Succession_Season_1_Complete.torrent")
      .readAllBytes()
  )

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

  test("verify assertions for torrent3") {
    torrent3 match {
      case Left(value) => fail(s"Tested not attempted: '$value'.")
      case Right(swarmTorrent) =>
        val files = swarmTorrent.info match {
          case Torrent.SingleFile(_, path, _, _) => List(path)
          case Torrent.MultiFile(files, name, _, _) => files.map(_.path).map(name.resolve).toList
        }
        println(s"Number pieces: ${swarmTorrent.info.pieces.size}")
        println(
          swarmTorrent.info.pieces.zipWithIndex
            .map { case (_, idx) =>
              swarmTorrent
                .fileSlices(idx)
                .map(f =>
                  f.toList
                    .map { case Torrent.FileSlice(path, offset, size) => s"   $path, $offset, $size" }
                    .mkString("\n")
                )
                .getOrElse("ERROR")
            }
            .toList
            .mkString("\n")
        )
//        swarmTorrent.fileSlices(1750) match {
//          case Some(value) =>
//            value.toList.foreach { case Torrent.FileSlice(path, offset, size) =>
//              println(s"$path, $offset, $size")
//            }
//          case None => println("Nothing")
//        }
    }
  }
}
