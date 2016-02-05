package com.convergencelabs.server.frontend.realtime

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success

import com.convergencelabs.server.domain.AuthenticationError
import com.convergencelabs.server.domain.AuthenticationFailure
import com.convergencelabs.server.domain.AuthenticationResponse
import com.convergencelabs.server.domain.AuthenticationSuccess
import com.convergencelabs.server.domain.ClientDisconnected
import com.convergencelabs.server.domain.DomainFqn
import com.convergencelabs.server.domain.HandshakeFailure
import com.convergencelabs.server.domain.HandshakeRequest
import com.convergencelabs.server.domain.HandshakeResponse
import com.convergencelabs.server.domain.HandshakeSuccess
import com.convergencelabs.server.domain.PasswordAuthRequest
import com.convergencelabs.server.domain.TokenAuthRequest
import com.convergencelabs.server.util.concurrent.AskFuture

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.util.Timeout

object ClientActor {
  def props(
    domainManager: ActorRef,
    connection: ProtocolConnection,
    domainFqn: DomainFqn,
    handshakeTimeout: FiniteDuration): Props = Props(
    new ClientActor(domainManager, connection, domainFqn, handshakeTimeout))
}

class ClientActor(
  private[this] val domainManager: ActorRef,
  private[this] val connection: ProtocolConnection,
  private[this] val domainFqn: DomainFqn,
  private[this] val handshakeTimeout: FiniteDuration)
    extends Actor with ActorLogging {

  // FIXME hard-coded (used for auth and handshake)
  implicit val requestTimeout = Timeout(1 seconds)
  implicit val ec = context.dispatcher

  val handshakeTimeoutTask = context.system.scheduler.scheduleOnce(handshakeTimeout) {
    log.debug("Handshaked timeout")
    connection.abort("Handhsake timeout")
    context.stop(self)
  }

  private[this] val connectionManager = context.parent

  connection.eventHandler = { case event:ConnectionEvent => self ! event }

  var modelClient: ActorRef = _
  var domainActor: ActorRef = _
  var modelManagerActor: ActorRef = _
  var sessionId: String = _

  def receive:Receive = receiveWhileHandshaking

  def receiveWhileHandshaking: Receive = {
    case RequestReceived(message, replyCallback) if message.isInstanceOf[HandshakeRequestMessage] => {
      handshake(message.asInstanceOf[HandshakeRequestMessage], replyCallback)
    }
    case ConnectionClosed => onConnectionClosed()
    case ConnectionDropped => onConnectionDropped()
    case ConnectionError(message) => onConnectionError(message)
    case x:Any => invalidMessage(x)
  }

  def receiveWhileAuthenticating: Receive = {
    case RequestReceived(message, replyCallback) if message.isInstanceOf[AuthenticationRequestMessage] => {
      authenticate(message.asInstanceOf[AuthenticationRequestMessage], replyCallback)
    }
    case ConnectionClosed => onConnectionClosed()
    case ConnectionDropped => onConnectionDropped()
    case ConnectionError(message) => onConnectionError(message)
    case x:Any => invalidMessage(x)
  }

  def authenticate(requestMessage: AuthenticationRequestMessage, cb: ReplyCallback): Unit = {
    val message = requestMessage match {
      case AuthenticationRequestMessage("password", None, Some(username), Some(password)) => PasswordAuthRequest(username, password)
      case AuthenticationRequestMessage("token", Some(token), None, None) => TokenAuthRequest(token)
      case _ => ??? // todo invalid message
    }

    val future = domainActor ? message

    future.mapResponse[AuthenticationResponse] onComplete {
      case Success(AuthenticationSuccess(uid, username)) => {
        this.modelClient = context.actorOf(ModelClientActor.props(uid, sessionId, modelManagerActor))
        cb.reply(AuthenticationResponseMessage(true, Some(username)))
        context.become(receiveWhileAuthenticated)
      }
      case Success(AuthenticationFailure) => {
        cb.reply(AuthenticationResponseMessage(false, None))
      }
      case Success(AuthenticationError) => {
        cb.reply(AuthenticationResponseMessage(false, None)) // TODO do we want this to go back to the client as something else?
      }
      case Failure(cause) => {
        cb.reply(AuthenticationResponseMessage(false, None))
      }
    }
  }

  def handshake(request: HandshakeRequestMessage, cb: ReplyCallback): Unit = {
    val canceled = handshakeTimeoutTask.cancel()
    if (canceled) {
      val future = domainManager ? HandshakeRequest(domainFqn, self, request.reconnect, request.reconnectToken)
      future.mapResponse[HandshakeResponse] onComplete {
        case Success(HandshakeSuccess(sessionId, reconnectToken, domainActor, modelManagerActor)) => {
          this.sessionId = sessionId
          this.domainActor = domainActor
          this.modelManagerActor = modelManagerActor
          cb.reply(HandshakeResponseMessage(true, None, Some(sessionId), Some(reconnectToken)))
          context.become(receiveWhileAuthenticating)
        }
        case Success(HandshakeFailure(code, details)) => {
          cb.reply(HandshakeResponseMessage(false, Some(ErrorData(code, details)), None, None))
          connection.abort("handshake failure")
          context.stop(self)
        }
        case Failure(cause) => {
          cb.reply(HandshakeResponseMessage(false, Some(ErrorData("unknown", "uknown error")), None, None))
          connection.abort("handshake failure")
          context.stop(self)
        }
      }
    }
  }

  // scalastyle:off cyclomatic.complexity
  def receiveWhileAuthenticated: Receive = {
    case RequestReceived(message, replyPromise) if message.isInstanceOf[HandshakeRequestMessage] => invalidMessage(message)

    case message: OutgoingProtocolNormalMessage => onOutgoingMessage(message)
    case message: OutgoingProtocolRequestMessage => onOutgoingRequest(message)

    case message: MessageReceived => onMessageReceived(message)
    case message: RequestReceived => onRequestReceived(message)

    case ConnectionClosed => onConnectionClosed()
    case ConnectionDropped => onConnectionDropped()
    case ConnectionError(message) => onConnectionError(message)

    case x: Any => unhandled(x)
  }
  // scalastyle:on cyclomatic.complexity

  def onOutgoingMessage(message: OutgoingProtocolNormalMessage): Unit = {
    connection.send(message)
  }

  def onOutgoingRequest(message: OutgoingProtocolRequestMessage): Unit = {
    val askingActor = sender
    val f = connection.request(message)
    // FIXME should we allow them to specify what should be coming back.
    f.mapTo[IncomingProtocolResponseMessage] onComplete {
      case Success(response) => askingActor ! response
      case Failure(cause) => ??? // FIXME what do do on failure?
    }
  }

  private def onMessageReceived(message: MessageReceived): Unit = {
    message match {
      case MessageReceived(x) if x.isInstanceOf[IncomingModelNormalMessage] => modelClient.forward(message)
    }
  }

  private def onRequestReceived(message: RequestReceived): Unit = {
    message match {
      case RequestReceived(x, _) if x.isInstanceOf[IncomingModelRequestMessage] => modelClient.forward(message)
    }
  }

  // FIXME duplicate code.
  private def onConnectionClosed(): Unit = {
    log.debug("Connection Closed")
    if (domainActor != null) {
      domainActor ! ClientDisconnected(sessionId)
    }
    context.stop(self)
  }

  private def onConnectionDropped(): Unit = {
    log.debug("Connection Dropped")
    if (domainActor != null) {
      domainActor ! ClientDisconnected(sessionId)
    }
    context.stop(self)
  }

  private def onConnectionError(message: String): Unit = {
    log.debug("Connection Error")
    if (domainActor != null) {
      domainActor ! ClientDisconnected(sessionId)
    }
    context.stop(self)
  }

  private[this] def invalidMessage(message: Any): Unit = {
    connection.abort("Invalid message")
    context.stop(self)
  }

  override def postStop(): Unit = {
    if (!handshakeTimeoutTask.isCancelled) {
      handshakeTimeoutTask.cancel()
    }
  }
}
