package com.convergencelabs.server.domain

import java.io.StringReader
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import scala.annotation.implicitNotFound
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.jose4j.jwt.JwtClaims
import org.jose4j.jwt.consumer.InvalidJwtException
import org.jose4j.jwt.consumer.JwtConsumerBuilder
import com.convergencelabs.server.datastore.domain.DomainConfigStore
import com.convergencelabs.server.datastore.domain.DomainUserStore
import com.convergencelabs.server.util.TryWithResource
import akka.actor.ActorRef
import akka.actor.actorRef2Scala
import grizzled.slf4j.Logging
import scala.util.Try
import scala.reflect.ClassTag
import com.convergencelabs.server.datastore.domain.ApiKeyStore
import com.convergencelabs.server.datastore.CreateResult
import com.convergencelabs.server.datastore.CreateSuccess
import com.convergencelabs.server.datastore.NotFound
import com.convergencelabs.server.datastore.DuplicateValue
import com.convergencelabs.server.datastore.InvalidValue
import com.convergencelabs.server.datastore.domain.DomainUserStore.CreateDomainUser
import com.convergencelabs.server.domain.model.SessionKey

object AuthenticationHandler {
  val AdminKeyId = "ConvergenceAdminKey"
  val AllowedClockSkew = 30

  private val MaxSessionId = 2176782335L
  private val SessionIdRadix = 36
}

class AuthenticationHandler(
  private[this] val domainConfigStore: DomainConfigStore,
  private[this] val keyStore: ApiKeyStore,
  private[this] val userStore: DomainUserStore,
  private[this] implicit val ec: ExecutionContext)
    extends Logging {

  private[this] var nextSessionId = 0L

  def authenticate(request: AuthenticationRequest): Future[AuthenticationResponse] = {
    request match {
      case message: PasswordAuthRequest => authenticatePassword(message)
      case message: TokenAuthRequest => authenticateToken(message)
    }
  }

  private[this] def authenticatePassword(authRequest: PasswordAuthRequest): Future[AuthenticationResponse] = {
    val response = userStore.validateCredentials(authRequest.username, authRequest.password) match {
      case Success((true, Some(uid))) => AuthenticationSuccess(uid, authRequest.username, SessionKey(uid, generateNextSessionId()))
      case Success((false, _)) => AuthenticationFailure
      case Success((true, None)) => {
        // We validated the user, but could not get the user id.  This should not happen.
        logger.error(s"user with username '${authRequest.username}' had valid credentials, but a valid uid was not returned")
        AuthenticationError
      }
      case Failure(cause) => {
        logger.error("Unable to authenticate a user", cause)
        AuthenticationError
      }
    }

    Future.successful(response)
  }

  private[this] def authenticateToken(authRequest: TokenAuthRequest): Future[AuthenticationResponse] = {
    Future[AuthenticationResponse] {
      // This implements a two pass approach to be able to get the key id.
      val firstPassJwtConsumer = new JwtConsumerBuilder()
        .setSkipAllValidators()
        .setDisableRequireSignature()
        .setSkipSignatureVerification()
        .build()

      val jwtContext = firstPassJwtConsumer.process(authRequest.jwt)
      val objects = jwtContext.getJoseObjects()
      val keyId = objects.get(0).getKeyIdHeaderValue()

      getJWTPublicKey(keyId) match {
        case Some(publicKey) => authenticateTokenWithPublicKey(authRequest, publicKey)
        case None => AuthenticationFailure
      }
    }
  }

  private[this] def authenticateTokenWithPublicKey(authRequest: TokenAuthRequest, publicKey: PublicKey): AuthenticationResponse = {
    val jwtConsumer = new JwtConsumerBuilder()
      .setRequireExpirationTime()
      .setAllowedClockSkewInSeconds(AuthenticationHandler.AllowedClockSkew)
      .setRequireSubject()
      .setExpectedIssuer(JwtConstants.Issuer)
      .setExpectedAudience(JwtConstants.Audiance)
      .setVerificationKey(publicKey)
      .build()

    val jwtClaims = jwtConsumer.processToClaims(authRequest.jwt)

    val username = jwtClaims.getSubject()

    // FIXME in theory we should cache the token id for longer than the expiration to make
    // sure a replay attack is not possible.
    userStore.getDomainUserByUsername(username) match {
      case Success(Some(user)) => {
        AuthenticationSuccess(user.uid, user.username, SessionKey(user.uid, generateNextSessionId()))
      }
      case Success(None) => {
        createUserFromJWT(jwtClaims) match {
          case Success(CreateSuccess(uid)) => AuthenticationSuccess(uid, username, SessionKey(uid, generateNextSessionId()))
          // FIXME: Determine what to do on duplicate value exception
          case Success(DuplicateValue) => AuthenticationFailure
          case Success(InvalidValue) => AuthenticationFailure
          case Failure(cause) => AuthenticationFailure
        }
      }
      case Failure(cause) => AuthenticationFailure
    }
  }

  private[this] def createUserFromJWT(jwtClaims: JwtClaims): Try[CreateResult[String]] = {
    val username = jwtClaims.getSubject()
    val firstName = JwtUtil.getClaim[String](jwtClaims, JwtClaimConstants.FirstName)
    val lastName = JwtUtil.getClaim[String](jwtClaims, JwtClaimConstants.LastName)
    val email = JwtUtil.getClaim[String](jwtClaims, JwtClaimConstants.Email)
    val newUser = CreateDomainUser(username, firstName, lastName, email)
    userStore.createDomainUser(newUser, None)
  }

  private[this] def getJWTPublicKey(keyId: String): Option[PublicKey] = {
    val keyPem: Option[String] = if (!AuthenticationHandler.AdminKeyId.equals(keyId)) {
      keyStore.getKey(keyId) match {
        case Success(Some(key)) if key.enabled => Some(key.key)
        case _ => None
      }
    } else {
      domainConfigStore.getAdminKeyPair() match {
        case Success(keyPair) => Some(keyPair.publicKey)
        case _ =>
          logger.error("Unabled to load admin key for domain")
          None
      }
    }

    keyPem.flatMap { pem =>
      TryWithResource(new PEMParser(new StringReader(pem))) { pemReader =>
        val spec = new X509EncodedKeySpec(pemReader.readPemObject().getContent())
        val keyFactory = KeyFactory.getInstance("RSA", new BouncyCastleProvider())
        Some(keyFactory.generatePublic(spec))
      }.recoverWith {
        case e: Throwable =>
          logger.warn("Unabled to decode jwt public key: " + e.getMessage)
          Success(None)
      }.get
    }
  }

  private[this] def generateNextSessionId(): String = {
    val sessionId = nextSessionId

    if (nextSessionId < AuthenticationHandler.MaxSessionId) {
      nextSessionId += 1
    } else {
      nextSessionId = 0
    }

    java.lang.Long.toString(sessionId, AuthenticationHandler.SessionIdRadix)
  }
}