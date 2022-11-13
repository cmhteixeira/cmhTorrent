package com.cmhteixeira.bencode

import cats.Show
import cats.syntax.show
import com.cmhteixeira.bencode.Bencode.{BByteString, BDictionary, BInteger, BList}
import com.cmhteixeira.bencode.DecodingFailure.GenericDecodingFailure
import com.cmhteixeira.cmhtorrent.Torrent
import org.scalatest.{FunSuite, Matchers}
import sun.nio.cs.US_ASCII

class DecodeSpec extends FunSuite with Matchers {
  test("The number 42 can be encoded as 'i42e'") {
    bParse("i42e".getBytes(new US_ASCII())) shouldBe Right(BInteger(42L))
  }

  test("The negative of 42 can be encoded as 'i-42e'") {
    bParse("i-42e".getBytes(new US_ASCII())) shouldBe Right(BInteger(-42L))
  }

  test("The string 'spam' can be encoded as '4:spam'") {
    bParse("4:spam".getBytes(new US_ASCII())) match {
      case Left(value) => fail(value.toString)
      case Right(BByteString(theBytes)) => new String(theBytes, new US_ASCII()) shouldBe "spam"
      case Right(a) => fail(s"Decoded value should be a ${classOf[BByteString]}. Actual: ${a.toString}")
    }
  }

  test("The list consisting of the string 'spam' and the number 42 can be encoded as 'l4:spami42ee'") {
    bParse("l4:spami42ee".getBytes(new US_ASCII)) match {
      case Left(value) => fail(value.toString)
      case Right(BList(BByteString(byteString) :: BInteger(theNumber) :: Nil)) =>
        theNumber shouldBe 42L
        new String(byteString, new US_ASCII()) shouldBe "spam"
      case Right(a) => fail(s"Incorrectly decoded. Actual: ${a.toString}")
    }
  }

  test(
    "A dictionary that associates the values 42 and 'spam' with the keys 'foo' and 'bar' respectively can be encoded as 'd3:bar4:spam3:fooi42ee'"
  ) {
    bParse("d3:bar4:spam3:fooi42ee".getBytes(new US_ASCII)) match {
      case Left(value) => fail(value.toString)
      case Right(BDictionary(underlying)) =>
        val mapSize = underlying.size
        if (mapSize != 2) fail(s"Dictionary should contain 2 keys. Actual: $mapSize keys. Value: ${underlying}")
        else {
          val res = underlying.map { case (BByteString(a), value) => (new String(a, new US_ASCII()), value) }
          res.get("foo") match {
            case Some(BInteger(a)) => a shouldBe 42L
            case Some(a) => fail(s"Value for key 'foo' should be ${classOf[BInteger]}. Actual: ${a.toString}")
            case None => fail("No key 'foo'")
          }
          res.get("bar") match {
            case Some(BByteString(a)) => new String(a, new US_ASCII()) shouldBe "spam"
            case Some(value) =>
              fail(s"Value for key 'foo' should be ${classOf[BByteString]}. Actual: ${value.toString}")
            case None => fail("No key 'bar'")
          }

        }
      case Right(a) => fail(s"Incorrectly decoded. Actual: ${a.toString}")
    }
  }

  test("'d4:spaml1:a1:bee' should parse to a dictionary of key 'spam' and value a list with elements 'a' and 'b'") {
    bParse("d4:spaml1:a1:bee".getBytes(new US_ASCII())) match {
      case Left(value) => fail(value)
      case Right(BDictionary(underlying)) =>
        val sizeDict = underlying.size
        if (sizeDict != 1) fail(s"Dictionary should contain 1 key ('spam') only. Actual: $underlying")
        else {
          val (key, value) = underlying.head
          new String(key.underlying, new US_ASCII) shouldBe "spam"
          value match {
            case BList(BByteString(a) :: BByteString(b) :: Nil) =>
              new String(a, new US_ASCII) shouldBe "a"
              new String(b, new US_ASCII) shouldBe "b"
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
    bParse(r) match {
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
    bParse(r) match {
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
