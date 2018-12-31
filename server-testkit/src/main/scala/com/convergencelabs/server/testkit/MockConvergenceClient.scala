package com.convergencelabs.server.testkit

import java.net.URI
import java.util.concurrent.LinkedBlockingDeque

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_17
import org.java_websocket.handshake.ServerHandshake

import grizzled.slf4j.Logging
import scalapb.GeneratedMessage
import io.convergence.proto.Normal
import io.convergence.proto.Request
import io.convergence.proto.Response
import java.nio.ByteBuffer
import com.convergencelabs.server.frontend.realtime.ConvergenceMessageBodyUtils
import io.convergence.proto.message.ConvergenceMessage
import io.convergence.proto.connection.PongMessage

class MockConvergenceClient(serverUri: String)
  extends WebSocketClient(new URI(serverUri), new Draft_17())
  with Logging {

  private val queue = new LinkedBlockingDeque[ConvergenceMessage]()

  override def connect(): Unit = {
    logger.info("Connecting...")
    super.connect()
  }

  override def onOpen(handshakedata: ServerHandshake): Unit = {
    logger.info("Connection opened")
  }

  override def onClose(code: Int, reason: String, remote: Boolean): Unit = {
    logger.info("closed with exit code " + code + " additional info: " + reason);
  }

  def sendNormal(message: GeneratedMessage with Normal): ConvergenceMessage = {
    val convergenceMessage = ConvergenceMessage()
      .withBody(ConvergenceMessageBodyUtils.toBody(message))
    sendMessage(convergenceMessage)
    convergenceMessage
  }

  var reqId = 0

  def sendRequest(message: GeneratedMessage with Request): ConvergenceMessage = {
    val convergenceMessage = ConvergenceMessage()
      .withBody(ConvergenceMessageBodyUtils.toBody(message))
      .withRequestId(reqId)
    sendMessage(convergenceMessage)
    reqId = reqId + 1
    convergenceMessage
  }

  def sendResponse(reqId: Int, message: GeneratedMessage with Response): ConvergenceMessage = {
    val convergenceMessage = ConvergenceMessage()
      .withBody(ConvergenceMessageBodyUtils.toBody(message))
      .withResponseId(reqId)
    sendMessage(convergenceMessage)
    convergenceMessage
  }

  def sendMessage(message: ConvergenceMessage): Unit = {
    val bytes = message.toByteArray
    send(bytes)
    logger.debug("SEND: " + message)
  }
  
  override def onMessage(message: String): Unit = {
    throw new UnsupportedOperationException("The convergence protocol does not support text messages")
  }

  override def onMessage(bytes: ByteBuffer): Unit = {
    val received = ConvergenceMessage.parseFrom(bytes.array())
    logger.debug("RCV : " + received)

    if (received.body.isPing) {
      onPing()
    } else if (received.body.isPong) {
      // no-op
    } else {
      this.queue.add(received)
    }
  }

  def onPing(): Unit = {
    sendMessage(ConvergenceMessage().withPong(PongMessage()))
  }

  override def onError(ex: Exception): Unit = {
    logger.error("an error occurred", ex);
  }

  def expectMessage(max: FiniteDuration): ConvergenceMessage = receiveOne(max)

  def expectMessageClass[C <: GeneratedMessage](max: FiniteDuration, c: Class[C]): (C, ConvergenceMessage) =
    expectMessageClass_internal(max, c)

  private def expectMessageClass_internal[C <: GeneratedMessage](max: FiniteDuration, c: Class[C]): (C, ConvergenceMessage) = {
    val convergenceMessage = receiveOne(max)
    assert(convergenceMessage != null, s"timeout ($max) during expectMsgClass waiting for $c")

    val body = ConvergenceMessageBodyUtils.fromBody(convergenceMessage.body)

    assert(c isInstance body, s"expected $c, found ${body.getClass}")

    (body.asInstanceOf[C], convergenceMessage)
  }

  def receiveOne(max: Duration): ConvergenceMessage = {
    val envelope =
      if (max == 0.seconds) {
        queue.pollFirst
      } else if (max.isFinite) {
        queue.pollFirst(max.length, max.unit)
      } else {
        queue.takeFirst
      }

    assert(envelope ne null, s"timeout ($max) during receive one")
    envelope
  }
}