package com.cmhteixeira

import sun.nio.cs.US_ASCII
import com.cmhteixeira.bencode.Bencode._
import com.cmhteixeira.bencode.ParsingFailure.{
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

  def bParse(input: Array[Byte]): Either[ParsingFailure, Bencode] =
    bParse(input.map(_.toChar).toList).flatMap {
      case (bencode, Nil) => Right(bencode)
      case (_: BInteger, remaining) => Left(DataAfterInteger)
      case (_: BList, remaining) => Left(DataAfterList)
      case (_: BByteString, remaining) => Left(DataAfterByteString)
      case (_: BDictionary, remaining) => Left(DataAfterDictionary)
    }

  private def bParse(input: List[Char]): Either[ParsingFailure, (Bencode, List[Char])] = {
    input match {
      case 'i' :: '-' :: xs => bParseInt(xs, 0).map { case (a, b) => (BInteger(a.underlying * (-1L)), b) }
      case 'i' :: xs => bParseInt(xs, 0)
      case b @ a :: xs if a.isDigit => parseByteString(b)
      case 'l' :: xs => parseList(xs, List.empty)
      case 'd' :: xs => parseDict(xs, Map.empty)
    }
  }

  @tailrec
  private def bParseInt(in: List[Char], cumulative: Long): Either[ParsingFailure, (BInteger, List[Char])] =
    in match {
      case head :: tl if head.isDigit => bParseInt(tl, cumulative * 10 + head.asDigit)
      case head :: tl if head == 'e' => Right((BInteger(cumulative), tl))
      case _ => Left(BadInteger)
    }

  private def parseByteString(
      in: List[Char]
  ): Either[ParsingFailure, (BByteString, List[Char])] = {

    @tailrec
    def readPart1(in: List[Char], bytesToRead: Long): Either[ParsingFailure, (BByteString, List[Char])] =
      in match {
        case a :: xs if a.isDigit => readPart1(xs, bytesToRead * 10 + a.asDigit)
        case ':' :: rest =>
          val (a1, a2) = readPart2(rest, bytesToRead, List())
          Right(BByteString(a1.toArray), a2)
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
  private def parseList(in: List[Char], cumulative: List[Bencode]): Either[ParsingFailure, (BList, List[Char])] = {
    bParse(in) match {
      case Left(l) => Left(l)
      case Right((bencode, 'e' :: xs)) => Right(BList(cumulative :+ bencode), xs)
      case Right((bencode, xs)) => parseList(xs, cumulative :+ bencode)
    }
  }

  @tailrec
  private def parseDict(
      in: List[Char],
      cumulative: Map[BByteString, Bencode]
  ): Either[ParsingFailure, (BDictionary, List[Char])] =
    in match {
      case Nil => Right((BDictionary(cumulative), Nil))
      case 'e' :: rest => Right(BDictionary(cumulative), rest)
      case in =>
        parseByteString(in) match {
          case Left(value) => Left(value)
          case Right((key: BByteString, remaining)) =>
            bParse(remaining) match {
              case Left(value) => Left(value)
              case Right((bencode, remainingHere)) => parseDict(remainingHere, cumulative + (key -> bencode))
            }
          case Right((notByteString, remaining)) => Left(BadDictionary)
        }
    }

  def bEncode(b: Bencode): Array[Byte] =
    b match {
      case BInteger(underlying) => s"i${underlying}e".getBytes(new US_ASCII())
      case BByteString(underlying) => s"${underlying.length}:".getBytes(new US_ASCII) ++ underlying
      case BList(underlying) =>
        val contents = underlying.map(bEncode).foldLeft(Array.emptyByteArray)(_ ++ _)
        "l".getBytes(new US_ASCII) ++ contents ++ "e".getBytes(new US_ASCII)
      case BDictionary(underlying) =>
        // TODO: How to order dictionary keys lexicographically if we don't have the encoding? For now encoding is un-ordered.
        val contents = underlying.foldLeft(Array.emptyByteArray) {
          case (a, (b1, b2)) => a ++ bEncode(b1) ++ bEncode(b2)
        }
        "d".getBytes(new US_ASCII) ++ contents ++ "e".getBytes(new US_ASCII)
    }
}
