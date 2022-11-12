package com.cmhteixeira.bencode

import com.cmhteixeira.bencode.Bencode.{BenByteString, BenDictionary, BenInteger, BenList}
import org.scalatest.{FunSuite, Matchers}
import sun.nio.cs.US_ASCII

class DecodeSpec extends FunSuite with Matchers {
  test("The number 42 can be encoded as 'i42e'") {
    bDecode("i42e".getBytes(new US_ASCII())) shouldBe Right(BenInteger(42L))
  }

  test("The negative of 42 can be encoded as 'i-42e'") {
    bDecode("i-42e".getBytes(new US_ASCII())) shouldBe Right(BenInteger(-42L))
  }

  test("The string 'spam' can be encoded as '4:spam'") {
    bDecode("4:spam".getBytes(new US_ASCII())) match {
      case Left(value) => fail(value.toString)
      case Right(BenByteString(theBytes)) => new String(theBytes, new US_ASCII()) shouldBe "spam"
      case Right(a) => fail(s"Decoded value should be a ${classOf[BenByteString]}. Actual: ${a.toString}")
    }
  }

  test("The list consisting of the string 'spam' and the number 42 can be encoded as 'l4:spami42ee'") {
    bDecode("l4:spami42ee".getBytes(new US_ASCII)) match {
      case Left(value) => fail(value.toString)
      case Right(BenList(BenByteString(byteString) :: BenInteger(theNumber) :: Nil)) =>
        theNumber shouldBe 42L
        new String(byteString, new US_ASCII()) shouldBe "spam"
      case Right(a) => fail(s"Incorrectly decoded. Actual: ${a.toString}")
    }
  }

  test(
    "A dictionary that associates the values 42 and 'spam' with the keys 'foo' and 'bar' respectively can be encoded as 'd3:bar4:spam3:fooi42ee'"
  ) {
    bDecode("d3:bar4:spam3:fooi42ee".getBytes(new US_ASCII)) match {
      case Left(value) => fail(value.toString)
      case Right(BenDictionary(underlying)) =>
        val mapSize = underlying.size
        if (mapSize != 2) fail(s"Dictionary should contain 2 keys. Actual: $mapSize keys. Value: ${underlying}")
        else {
          val res = underlying.map { case (BenByteString(a), value) => (new String(a, new US_ASCII()), value) }
          res.get("foo") match {
            case Some(BenInteger(a)) => a shouldBe 42L
            case Some(a) => fail(s"Value for key 'foo' should be ${classOf[BenInteger]}. Actual: ${a.toString}")
            case None => fail("No key 'foo'")
          }
          res.get("bar") match {
            case Some(BenByteString(a)) => new String(a, new US_ASCII()) shouldBe "spam"
            case Some(value) =>
              fail(s"Value for key 'foo' should be ${classOf[BenByteString]}. Actual: ${value.toString}")
            case None => fail("No key 'bar'")
          }

        }
      case Right(a) => fail(s"Incorrectly decoded. Actual: ${a.toString}")
    }
  }
}
