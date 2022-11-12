package com.cmhteixeira

import sun.nio.cs.US_ASCII
import com.cmhteixeira.bencode.Bencode._
import com.cmhteixeira.bencode.ParsingError.{
  BadByteString,
  BadDictionary,
  BadInteger,
  DataAfterByteString,
  DataAfterDictionary,
  DataAfterInteger,
  DataAfterList
}

import scala.annotation.tailrec

package object bencode {

  def bDecode(input: Array[Byte]): Either[ParsingError, Bencode] =
    bDecode2(input.map(_.toChar).toList).flatMap {
      case (bencode, Nil) => Right(bencode)
      case (_: BenInteger, remaining) => Left(DataAfterInteger)
      case (_: BenList, remaining) => Left(DataAfterList)
      case (_: BenByteString, remaining) => Left(DataAfterByteString)
      case (_: BenDictionary, remaining) => Left(DataAfterDictionary)
    }

  private def bDecode2(input: List[Char]): Either[ParsingError, (Bencode, List[Char])] = {
    input match {
      case 'i' :: '-' :: xs => bDecodeInt(xs, 0).map { case (a, b) => (BenInteger(a.underlying * (-1L)), b) }
      case 'i' :: xs => bDecodeInt(xs, 0)
      case b @ a :: xs if a.isDigit => decodeByteString(b)
      case 'l' :: xs => decodeList(xs, List.empty)
      case 'd' :: xs => decodeDict(xs, Map.empty)
    }
  }

  @tailrec
  private def bDecodeInt(in: List[Char], cumulative: Long): Either[ParsingError, (BenInteger, List[Char])] =
    in match {
      case head :: tl if head.isDigit => bDecodeInt(tl, cumulative * 10 + head.asDigit)
      case head :: tl if head == 'e' => Right((BenInteger(cumulative), tl))
      case _ => Left(BadInteger)
    }

  private def decodeByteString(
      in: List[Char]
  ): Either[ParsingError, (BenByteString, List[Char])] = {

    @tailrec
    def readPart1(in: List[Char], bytesToRead: Long): Either[ParsingError, (BenByteString, List[Char])] =
      in match {
        case a :: xs if a.isDigit => readPart1(xs, bytesToRead * 10 + a.asDigit)
        case ':' :: rest =>
          val (a1, a2) = readPart2(rest, bytesToRead, List())
          Right(BenByteString(a1.toArray), a2)
        case _ => Left(BadByteString)
      }

    @tailrec
    def readPart2(in: List[Char], counter: Long, cumulative: List[Byte]): (List[Byte], List[Char]) = {
      if (counter == 0) (cumulative, in)
      else
        in match {
          case Nil => (cumulative, Nil)
          case head :: tail => readPart2(tail, counter - 1, cumulative :+ head.toByte)
        }
    }

    readPart1(in, 0)
  }

  @tailrec
  private def decodeList(in: List[Char], cumulative: List[Bencode]): Either[ParsingError, (BenList, List[Char])] = {
    bDecode2(in) match {
      case Left(l) => Left(l)
      case Right((bencode, 'e' :: xs)) => Right(BenList(cumulative :+ bencode), xs)
      case Right((bencode, xs)) => decodeList(xs, cumulative :+ bencode)
    }
  }

  @tailrec
  private def decodeDict(
      in: List[Char],
      cumulative: Map[BenByteString, Bencode]
  ): Either[ParsingError, (BenDictionary, List[Char])] =
    in match {
      case Nil => Right((BenDictionary(cumulative), Nil))
      case 'e' :: rest => Right(BenDictionary(cumulative), rest)
      case in =>
        decodeByteString(in) match {
          case Left(value) => Left(value)
          case Right((key: BenByteString, remaining)) =>
            bDecode2(remaining) match {
              case Left(value) => Left(value)
              case Right((bencode, remainingHere)) => decodeDict(remainingHere, cumulative + (key -> bencode))
            }
          case Right((notByteString, remaining)) => Left(BadDictionary)
        }
    }

  def bEncode(b: Bencode): Array[Byte] =
    b match {
      case BenInteger(underlying) => s"i${underlying}e".getBytes(new US_ASCII())
      case BenByteString(underlying) => s"${underlying.length}:".getBytes(new US_ASCII) ++ underlying
      case BenList(underlying) =>
        val contents = underlying.map(bEncode).foldLeft(Array.emptyByteArray)(_ ++ _)
        "l".getBytes(new US_ASCII) ++ contents ++ "e".getBytes(new US_ASCII)
      case BenDictionary(underlying) =>
        // TODO: How to order dictionary keys lexicographically if we don't have the encoding? For now encoding is un-ordered.
        val contents = underlying.foldLeft(Array.emptyByteArray) {
          case (a, (b1, b2)) => a ++ bEncode(b1) ++ bEncode(b2)
        }
        "d".getBytes(new US_ASCII) ++ contents ++ "e".getBytes(new US_ASCII)
    }
}
