package com.cmhteixeira.bencode

import cats.Show
import com.cmhteixeira.bencode.Bencode.{BByteString, BDictionary, BInteger, BList}
import com.cmhteixeira.bencode.DecodingFailure.GenericDecodingFailure
import com.cmhteixeira.cmhtorrent.Torrent
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets

class DecodeSpec extends AnyFunSuite with Matchers {
  test("The number 42 can be encoded as 'i42e'") {
    parse("i42e".getBytes(StandardCharsets.US_ASCII)) shouldBe Right(BInteger(42L))
  }

  test("The negative of 42 can be encoded as 'i-42e'") {
    parse("i-42e".getBytes(StandardCharsets.US_ASCII)) shouldBe Right(BInteger(-42L))
  }

  test("The string 'spam' can be encoded as '4:spam'") {
    parse("4:spam".getBytes(StandardCharsets.US_ASCII)) match {
      case Left(value) => fail(value.toString)
      case Right(BByteString(theBytes)) => new String(theBytes, StandardCharsets.US_ASCII) shouldBe "spam"
      case Right(a) => fail(s"Decoded value should be a ${classOf[BByteString]}. Actual: ${a.toString}")
    }
  }

  test("The list consisting of the string 'spam' and the number 42 can be encoded as 'l4:spami42ee'") {
    parse("l4:spami42ee".getBytes(StandardCharsets.US_ASCII)) match {
      case Left(value) => fail(value.toString)
      case Right(BList(BByteString(byteString) :: BInteger(theNumber) :: Nil)) =>
        theNumber shouldBe 42L
        new String(byteString, StandardCharsets.US_ASCII) shouldBe "spam"
      case Right(a) => fail(s"Incorrectly decoded. Actual: ${a.toString}")
    }
  }

  test(
    "A dictionary that associates the values 42 and 'spam' with the keys 'foo' and 'bar' respectively can be encoded as 'd3:bar4:spam3:fooi42ee'"
  ) {
    parse("d3:bar4:spam3:fooi42ee".getBytes(StandardCharsets.US_ASCII)) match {
      case Left(value) => fail(value.toString)
      case Right(BDictionary(underlying)) =>
        val mapSize = underlying.size
        if (mapSize != 2) fail(s"Dictionary should contain 2 keys. Actual: $mapSize keys. Value: ${underlying}")
        else {
          val res = underlying.map { case (BByteString(a), value) => (new String(a, StandardCharsets.US_ASCII), value) }
          res.get("foo") match {
            case Some(BInteger(a)) => a shouldBe 42L
            case Some(a) => fail(s"Value for key 'foo' should be ${classOf[BInteger]}. Actual: ${a.toString}")
            case None => fail("No key 'foo'")
          }
          res.get("bar") match {
            case Some(BByteString(a)) => new String(a, StandardCharsets.US_ASCII) shouldBe "spam"
            case Some(value) =>
              fail(s"Value for key 'foo' should be ${classOf[BByteString]}. Actual: ${value.toString}")
            case None => fail("No key 'bar'")
          }

        }
      case Right(a) => fail(s"Incorrectly decoded. Actual: ${a.toString}")
    }
  }

  test("'d4:spaml1:a1:bee' should parse to a dictionary of key 'spam' and value a list with elements 'a' and 'b'") {
    parse("d4:spaml1:a1:bee".getBytes(StandardCharsets.US_ASCII)) match {
      case Left(value) => fail(value)
      case Right(BDictionary(underlying)) =>
        val sizeDict = underlying.size
        if (sizeDict != 1) fail(s"Dictionary should contain 1 key ('spam') only. Actual: $underlying")
        else {
          val (key, value) = underlying.head
          new String(key.underlying, StandardCharsets.US_ASCII) shouldBe "spam"
          value match {
            case BList(BByteString(a) :: BByteString(b) :: Nil) =>
              new String(a, StandardCharsets.US_ASCII) shouldBe "a"
              new String(b, StandardCharsets.US_ASCII) shouldBe "b"
            case _ =>
              fail(
                s"Incorrectly decoded value of the map element. Expected a list with 2 elements. Actual: ${value.toString}"
              )
          }
        }
      case Right(value) => fail(s"Incorrectly decoded. Actual: ${value.toString}")
    }
  }

  test("FOOBAR") {
    val r = getClass.getResourceAsStream("/clonezillaTorrent.torrent").readAllBytes()
    val decoder = implicitly[Decoder[Torrent]]
    parse(r) match {
      case Left(value) => println(value)
      case Right(value) =>
        decoder(value) match {
          case Left(value) =>
            println(value.asInstanceOf[GenericDecodingFailure].msg)
          case Right(value) =>
            println(Show[Torrent].show(value))
        }
    }
  }

  test("This should work") {
    val r = getClass.getResourceAsStream("/Black.Adam.(2022).[720p].[WEBRip].[YTS].torrent").readAllBytes()
    val decoder = implicitly[Decoder[Torrent]]
    parse(r) match {
      case Left(value) => println(value)
      case Right(value) =>
        decoder(value) match {
          case Left(value) =>
            println(value.asInstanceOf[GenericDecodingFailure].msg)
          case Right(value) =>
            println(Show[Torrent].show(value))
        }
    }
  }

}
