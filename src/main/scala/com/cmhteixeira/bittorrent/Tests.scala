package com.cmhteixeira.bittorrent

import cats.syntax.show

import java.io.FileInputStream
import scala.io.Source
import com.cmhteixeira.bencode._
import com.cmhteixeira.bittorrent.tracker.{ConnectRequest, ConnectResponse, UdpTrackerImpl}
import com.cmhteixeira.cmhtorrent.Torrent
import io.circe.Json
import sun.nio.cs.UTF_8

import java.net.{DatagramPacket, DatagramSocket, InetAddress, SocketAddress, URI, URL}
import java.nio.file.{Files, Paths}

object Tests extends App {
  val json: Json = Json.arr(Json.arr(Json.fromInt(0), Json.fromInt(1), Json.fromInt(2)))
  val res = json.as[List[List[Json]]]

  println(show.toShow(res.getOrElse(???).head).show)
}
