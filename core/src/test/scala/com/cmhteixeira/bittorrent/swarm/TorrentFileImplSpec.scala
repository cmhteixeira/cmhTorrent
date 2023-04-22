package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import scodec.bits.ByteVector
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Path, Paths}
import java.util.UUID

class TorrentFileImplSpec extends AnyFunSuite with Matchers {
  val temporaryFilesDir: Path = Paths.get(System.getProperty("java.io.tmpdir"))
  def randomTempFile(): Path = temporaryFilesDir.resolve(UUID.randomUUID().toString)

  test("Writing at an offset greater than the current file size should grow the file") {
    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(4, ByteVector.high(3))
    torrentFile.read(0, 7).isRight shouldBe true
  }

  test("Reading a slice up to the file edge should succeed.") {
    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(4, ByteVector.high(3))
    torrentFile.read(0, 7).isRight shouldBe true
  }

  test("Reading a slice over the file edge should fail.") {
    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(4, ByteVector.high(3))
    torrentFile.read(0, 8) shouldBe Left(TorrentFile.ReadError.OutOfBounds(7))
  }

  test("Writes can overlap") {
    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(4, ByteVector.high(3))
    torrentFile.write(5, ByteVector.high(1)).isRight shouldBe true
  }

  test("Writes can overlap and also grow the file") {
    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(4, ByteVector.high(3))
    torrentFile.write(5, ByteVector.high(3))
    torrentFile.read(6, 2).isRight shouldBe true
    torrentFile.read(6, 3) shouldBe Left(TorrentFile.ReadError.OutOfBounds(8))
  }

  test(
    "After a slice is finalized, another slice that terminates but does not begin in it, cannot be written into it."
  ) {
    import TorrentFile.Overlap
    import TorrentFile.WriteError.AlreadyFinalized

    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(4, ByteVector.high(3))
    torrentFile.finalizeSlice(4, 3)
    torrentFile.write(3, ByteVector.high(2)) shouldBe Left(
      AlreadyFinalized(NonEmptyList.of(Overlap(0, 4, false), Overlap(4, 3, true)))
    )
  }

  test(
    "After a slice is finalized, another slice that begins but does not terminate in it, cannot be written into it."
  ) {
    import TorrentFile.Overlap
    import TorrentFile.WriteError.AlreadyFinalized

    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(11, ByteVector.high(3))
    torrentFile.finalizeSlice(6, 4)
    torrentFile.write(9, ByteVector.high(3)) shouldBe Left(
      AlreadyFinalized(NonEmptyList.of(Overlap(6, 4, true), Overlap(10, 4, false)))
    )
  }

  test(
    "After a slice is finalized, another slice that begins and ends in it, cannot be written into it."
  ) {
    import TorrentFile.Overlap
    import TorrentFile.WriteError.AlreadyFinalized

    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(11, ByteVector.high(3))
    torrentFile.finalizeSlice(6, 4)
    torrentFile.write(7, ByteVector.high(3)) shouldBe Left(
      AlreadyFinalized(NonEmptyList.of(Overlap(6, 4, true)))
    )
  }

  test(
    "The overlap of a write against all the affected slices should be listed whe it exists."
  ) {
    import TorrentFile.Overlap
    import TorrentFile.WriteError.AlreadyFinalized

    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(0, ByteVector.high(15))
    torrentFile.finalizeSlice(3, 3)
    torrentFile.finalizeSlice(7, 1)
    torrentFile.write(2, ByteVector.high(7)) shouldBe Left(
      AlreadyFinalized(
        NonEmptyList.of(
          Overlap(0, 3, false),
          Overlap(3, 3, true),
          Overlap(6, 1, false),
          Overlap(7, 1, true),
          Overlap(8, 7, false)
        )
      )
    )
  }

  test(
    "Writes are allowed immediately after a finalized slice"
  ) {
    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(0, ByteVector.high(15))
    torrentFile.finalizeSlice(3, 3)
    torrentFile.write(6, ByteVector.high(2)).isRight shouldBe true
  }

  test(
    "Writes are allowed up to the beginning of a finalized slice"
  ) {
    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(0, ByteVector.high(15))
    torrentFile.finalizeSlice(3, 3)
    torrentFile.write(1, ByteVector.high(2)).isRight shouldBe true
  }

  test(
    "After a slice is finalized, another slice that terminates but does not begin in it, cannot be finalized."
  ) {
    import TorrentFile.Overlap
    import TorrentFile.FinalizeError.AlreadyFinalized

    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(4, ByteVector.high(3))
    torrentFile.finalizeSlice(4, 3)
    torrentFile.finalizeSlice(3, 2) shouldBe Left(
      AlreadyFinalized(NonEmptyList.of(Overlap(0, 4, false), Overlap(4, 3, true)))
    )
  }

  test(
    "After a slice is finalized, another slice that begins but does not terminate in it, cannot be finalized."
  ) {
    import TorrentFile.Overlap
    import TorrentFile.FinalizeError.AlreadyFinalized

    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(11, ByteVector.high(3))
    torrentFile.finalizeSlice(6, 4)
    torrentFile.finalizeSlice(9, 3) shouldBe Left(
      AlreadyFinalized(NonEmptyList.of(Overlap(6, 4, true), Overlap(10, 4, false)))
    )
  }

  test(
    "After a slice is finalized, another slice that begins and ends in it, cannot be finalized."
  ) {
    import TorrentFile.Overlap
    import TorrentFile.FinalizeError.AlreadyFinalized

    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(11, ByteVector.high(3))
    torrentFile.finalizeSlice(6, 4)
    torrentFile.finalizeSlice(7, 3) shouldBe Left(
      AlreadyFinalized(NonEmptyList.of(Overlap(6, 4, true)))
    )
  }

  test(
    "The overlap of a finalize against all the affected slices should be listed whe it exists."
  ) {
    import TorrentFile.Overlap
    import TorrentFile.FinalizeError.AlreadyFinalized

    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(0, ByteVector.high(15))
    torrentFile.finalizeSlice(3, 3)
    torrentFile.finalizeSlice(7, 1)
    torrentFile.finalizeSlice(2, 7) shouldBe Left(
      AlreadyFinalized(
        NonEmptyList.of(
          Overlap(0, 3, false),
          Overlap(3, 3, true),
          Overlap(6, 1, false),
          Overlap(7, 1, true),
          Overlap(8, 7, false)
        )
      )
    )
  }

  test(
    "We can finalize a slice immediately after another slice already finalized."
  ) {
    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(0, ByteVector.high(15))
    torrentFile.finalizeSlice(3, 3)
    torrentFile.finalizeSlice(6, 2).isRight shouldBe true
  }

  test(
    "We can finalize a slice right up to slice already finalized"
  ) {
    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(0, ByteVector.high(15))
    torrentFile.finalizeSlice(3, 3)
    torrentFile.finalizeSlice(1, 2).isRight shouldBe true
  }

  test("Cannot finalize beyond file limits") {
    import TorrentFile.FinalizeError.OutOfBounds

    val filePath = randomTempFile()
    val torrentFile = TorrentFileImpl(filePath).get

    torrentFile.write(0, ByteVector.high(7))
    torrentFile.finalizeSlice(4, 4) shouldBe Left(OutOfBounds(7))
  }

}
