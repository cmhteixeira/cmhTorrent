package com.cmhteixeira.bittorrent.tracker.core

import com.cmhteixeira.bittorrent.tracker.core.TrackerResponseStream.{State, maximumUdpPacketSize}
import com.cmhteixeira.bittorrent.tracker.{AnnounceResponse, ConnectResponse, TrackerResponse}
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import org.slf4j.LoggerFactory

import java.net.{DatagramPacket, DatagramSocket, InetSocketAddress}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

final class TrackerResponseStream private (
    udpSocket: DatagramSocket,
    subscriber: AtomicReference[State]
) extends Publisher[TrackerResponse] {
  private val logger = LoggerFactory.getLogger("TrackerReader")

  private def infiniteLoop(): Runnable = new Runnable {
    @tailrec
    override def run(): Unit = {
      if (Thread.currentThread().isInterrupted) logger.info("Terminating ...")
      else {
        val packet = new DatagramPacket(ByteBuffer.allocate(maximumUdpPacketSize).array(), maximumUdpPacketSize)
        Try(udpSocket.receive(packet)) match {
          case Failure(e: InterruptedException) => logger.info("Terminating ....", e)
          case Failure(exception) =>
            logger.warn("Called receive on socket. Waiting 500 milliseconds and then continuing.", exception)
            Try(Thread.sleep(500)) match {
              case Failure(exception) => logger.info("Terminating ....", exception)
              case Success(_) => ()
            }
            run()
          // todo: How to close this?
          case Success(_) =>
            processPacket(packet)
            run()
        }
      }
    }
  }

  private def processPacket(dg: DatagramPacket): Unit = {
    val payloadSize = dg.getLength
    val origin = dg.getSocketAddress.asInstanceOf[InetSocketAddress]
    if (payloadSize == 16) // could be ConnectResponse
      ConnectResponse.deserialize(dg.getData) match {
        case Left(e) =>
          logger.warn(s"Received 16 bytes packet from '$origin' but deserialization to connect response failed: '$e'.")
        case Right(connectResponse) =>
          logger.info(s"Received potential Connect response from '$origin'.")
          sendToSubscriber(TrackerResponse.ConnectReceived(origin, connectResponse, System.nanoTime()))
      }
    else if (payloadSize >= 20 && (payloadSize - 20) % 6 == 0) // could be an AnnounceResponse
      AnnounceResponse.deserialize(dg.getData, payloadSize) match {
        case Left(e) =>
          logger.warn(
            s"Received $payloadSize bytes packet from '$origin' but deserialization to announce response failed: '$e'."
          )
        case Right(announceResponse) =>
          logger.info(s"Received potential Announce response from '$origin' with $payloadSize bytes.")
          sendToSubscriber(TrackerResponse.AnnounceReceived(origin, announceResponse))
      }
    else
      logger.warn(
        s"Received $payloadSize bytes packet from '$origin'. Does not conform to 'ConnectResponse' or 'AnnounceResponse'."
      )
  }

  private def sendToSubscriber(msg: TrackerResponse): Unit =
    subscriber.get() match {
      case State.Active(s, _) =>
        Try(s.onNext(msg)) match {
          case Failure(exception) => logger.info(s"Failed onNext with $msg", exception)
          case Success(_) => ()
        }
      case otherState => logger.info(s"Will not send, as in state: $otherState")
    }

  private def subscribeInternal(s: Subscriber[TrackerResponse]): Unit = {
    val currentState = subscriber.get()
    currentState match {
      case State.Off =>
        if (!subscriber.compareAndSet(currentState, State.Inactive(s))) subscribe(s)
        else s.onSubscribe(new TrackerSubscription(cancelled = false))
      case _ => s.onError(new IllegalStateException("Already subscribed."))
    }
  }

  override def subscribe(s: Subscriber[_ >: TrackerResponse]): Unit = {
    if (s == null) throw new NullPointerException("Subscriber cannot be null.")
    subscribeInternal(s.asInstanceOf[Subscriber[TrackerResponse]])
  }

  private class TrackerSubscription(@volatile var cancelled: Boolean) extends Subscription {

    def request(n: Long): Unit = {
      if (!cancelled) {
        val currentState = subscriber.get()
        currentState match {
          case State.Off => logger.info("Requesting, but no subscription ...")
          case State.Inactive(s) =>
            if (n <= Long.MaxValue - 1) {
              s.onError(new IllegalArgumentException(s"Demand must be unbounded. Provided: $n"))
              cancel()
            }
            val handler = new Thread(infiniteLoop(), TrackerResponseStream.threadName)
            logger.info(s"Requesting elements. Starting thread $handler")
            subscriber.set(State.Active(s, handler))
            handler.start()
          case State.Active(s, _) =>
            if (n <= 0) {
              s.onError(new IllegalArgumentException(s"Demand must be unbounded. Provided: $n"))
              cancel()
            }
        }
      }
    }
    override def cancel(): Unit = {
      if (!cancelled) {
        logger.info("Cancelling subscription ...")
        val currentState = subscriber.get()
        currentState match {
          case State.Off => logger.info("No subscription found ...")
          case State.Inactive(_) =>
            logger.info("Simply removing subscriber from state ...")
            cancelled = true
            subscriber.set(State.Off)
          case State.Active(_, threadHandle) =>
            logger.info("Interrupting thread, waiting for termination, and removing subscriber from state.")
            cancelled = true
            threadHandle.interrupt()
            threadHandle.join()
            subscriber.set(State.Off)
        }
      }
    }
  }
}

object TrackerResponseStream {

  private val threadName = "tracker-udp-socket-reads"
  sealed trait State
  object State {
    case object Off extends State
    case class Inactive(s: Subscriber[TrackerResponse]) extends State
    case class Active(s: Subscriber[TrackerResponse], threadHandle: Thread) extends State
  }

  private val maximumUdpPacketSize = 65507

  def apply(udpSocket: DatagramSocket) = new TrackerResponseStream(udpSocket, new AtomicReference[State](State.Off))
}
