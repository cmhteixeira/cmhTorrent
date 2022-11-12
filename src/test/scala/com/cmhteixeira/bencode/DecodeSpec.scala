package com.cmhteixeira.bencode

import com.cmhteixeira.bencode.Bencode.{BenByteString, BenInteger}
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
}
