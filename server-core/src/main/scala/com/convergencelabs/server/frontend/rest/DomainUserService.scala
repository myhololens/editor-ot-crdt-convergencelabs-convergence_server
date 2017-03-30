package com.convergencelabs.server.frontend.rest

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

import com.convergencelabs.server.datastore.UserStoreActor.CreateUser
import com.convergencelabs.server.datastore.UserStoreActor.DeleteDomainUser
import com.convergencelabs.server.datastore.UserStoreActor.GetUserByUsername
import com.convergencelabs.server.datastore.UserStoreActor.GetUsers
import com.convergencelabs.server.datastore.UserStoreActor.SetPassword
import com.convergencelabs.server.datastore.UserStoreActor.UpdateUser
import com.convergencelabs.server.domain.AuthorizationActor.ConvergenceAuthorizedRequest
import com.convergencelabs.server.domain.DomainFqn
import com.convergencelabs.server.domain.DomainUser
import com.convergencelabs.server.domain.RestDomainManagerActor.DomainMessage
import com.convergencelabs.server.frontend.rest.DomainUserService.CreateUserRequest
import com.convergencelabs.server.frontend.rest.DomainUserService.CreateUserResponse
import com.convergencelabs.server.frontend.rest.DomainUserService.GetUserRestResponse
import com.convergencelabs.server.frontend.rest.DomainUserService.SetPasswordRequest
import com.convergencelabs.server.frontend.rest.DomainUserService.UpdateUserRequest

import DomainUserService.GetUsersRestResponse
import akka.actor.ActorRef
import akka.http.scaladsl.marshalling.ToResponseMarshallable.apply
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive.addByNameNullaryApply
import akka.http.scaladsl.server.Directive.addDirectiveApply
import akka.http.scaladsl.server.Directives.Segment
import akka.http.scaladsl.server.Directives._enhanceRouteWithConcatenation
import akka.http.scaladsl.server.Directives._segmentStringToPathMatcher
import akka.http.scaladsl.server.Directives._string2NR
import akka.http.scaladsl.server.Directives.as
import akka.http.scaladsl.server.Directives.authorizeAsync
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.Directives.delete
import akka.http.scaladsl.server.Directives.entity
import akka.http.scaladsl.server.Directives.get
import akka.http.scaladsl.server.Directives.parameters
import akka.http.scaladsl.server.Directives.pathEnd
import akka.http.scaladsl.server.Directives.pathPrefix
import akka.http.scaladsl.server.Directives.post
import akka.http.scaladsl.server.Directives.put
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import com.convergencelabs.server.domain.model.query.Ast.OrderBy
import com.convergencelabs.server.datastore.domain.DomainUserField
import com.convergencelabs.server.datastore.SortOrder

case class DomainUserData(
  username: String,
  firstName: Option[String],
  lastName: Option[String],
  displayName: Option[String],
  email: Option[String])

object DomainUserService {
  case class CreateUserRequest(username: String, firstName: Option[String], lastName: Option[String], displayName: Option[String], email: Option[String], password: Option[String])
  case class CreateUserResponse() extends AbstractSuccessResponse
  case class GetUsersRestResponse(users: List[DomainUserData]) extends AbstractSuccessResponse
  case class GetUserRestResponse(user: DomainUserData) extends AbstractSuccessResponse
  case class UpdateUserRequest(
    firstName: Option[String],
    lastName: Option[String],
    displayName: Option[String],
    email: Option[String])
  case class SetPasswordRequest(password: String)

}

class DomainUserService(
  private[this] val executionContext: ExecutionContext,
  private[this] val authorizationActor: ActorRef,
  private[this] val domainRestActor: ActorRef,
  private[this] val defaultTimeout: Timeout)
    extends JsonSupport {

  implicit val ec = executionContext
  implicit val t = defaultTimeout
//    orderBy: Option[DomainUserField.Field],
//    sortOrder: Option[SortOrder.Value],
//    limit: Option[Int],
//    offset: Option[Int]
  def route(convergenceUsername: String, domain: DomainFqn): Route = {
    pathPrefix("users") {
      pathEnd {
        get {
          parameters("filter".?, "limit".as[Int].?, "offset".as[Int].?) { (filter, limit, offset) =>
            authorizeAsync(canAccessDomain(domain, convergenceUsername)) {
              complete(getAllUsersRequest(domain, filter, limit, offset))
            }
          }
        } ~ post {
          entity(as[CreateUserRequest]) { request =>
            authorizeAsync(canAccessDomain(domain, convergenceUsername)) {
              complete(createUserRequest(request, domain))
            }
          }
        }
      } ~ pathPrefix(Segment) { domainUsername =>
        pathEnd {
          get {
            authorizeAsync(canAccessDomain(domain, convergenceUsername)) {
              complete(getUserByUsername(domainUsername, domain))
            }
          } ~ delete {
            authorizeAsync(canAccessDomain(domain, convergenceUsername)) {
              complete(deleteUser(domainUsername, domain))
            }
          } ~ put {
            entity(as[UpdateUserRequest]) { request =>
              authorizeAsync(canAccessDomain(domain, convergenceUsername)) {
                complete(updateUserRequest(domainUsername, request, domain))
              }
            }
          }
        } ~ pathPrefix("password") {
          pathEnd {
            put {
              entity(as[SetPasswordRequest]) { request =>
                authorizeAsync(canAccessDomain(domain, convergenceUsername)) {
                  complete(setPasswordRequest(domainUsername, request, domain))
                }
              }
            }
          }
        }
      }
    }
  }

  def getAllUsersRequest(domain: DomainFqn, filter: Option[String], limit: Option[Int], offset: Option[Int]): Future[RestResponse] = {
    (domainRestActor ? DomainMessage(domain, GetUsers(filter, limit, offset))).mapTo[List[DomainUser]] map
      (users => (StatusCodes.OK, GetUsersRestResponse(users.map(toUserData(_)))))
  }

  def createUserRequest(createRequest: CreateUserRequest, domain: DomainFqn): Future[RestResponse] = {
    val CreateUserRequest(username, firstName, lastName, displayName, email, password) = createRequest
    val message = DomainMessage(domain, CreateUser(username, firstName, lastName, displayName, email, password))
    (domainRestActor ? message) map { _ => (StatusCodes.Created, CreateUserResponse()) }
  }

  def updateUserRequest(username: String, updateRequest: UpdateUserRequest, domain: DomainFqn): Future[RestResponse] = {
    val UpdateUserRequest(firstName, lastName, displayName, email) = updateRequest
    val message = DomainMessage(domain, UpdateUser(username, firstName, lastName, displayName, email))
    (domainRestActor ? message) map { _ => OkResponse }
  }

  def setPasswordRequest(uid: String, setPasswordRequest: SetPasswordRequest, domain: DomainFqn): Future[RestResponse] = {
    val message = DomainMessage(domain, SetPassword(uid, setPasswordRequest.password))
    (domainRestActor ? message) map { _ => OkResponse }
  }

  def getUserByUsername(username: String, domain: DomainFqn): Future[RestResponse] = {
    (domainRestActor ? DomainMessage(domain, GetUserByUsername(username))).mapTo[Option[DomainUser]] map {
      case Some(user) =>
        (StatusCodes.OK, GetUserRestResponse(toUserData(user)))
      case None =>
        NotFoundError
    }
  }

  def deleteUser(uid: String, domain: DomainFqn): Future[RestResponse] = {
    (domainRestActor ? DomainMessage(domain, DeleteDomainUser(uid))) map { _ => OkResponse }
  }

  private[this] def toUserData(user: DomainUser): DomainUserData = {
    val DomainUser(userType, username, firstName, lastName, displayName, email) = user
    DomainUserData(username, firstName, lastName, displayName, email)
  }

  // Permission Checks

  def canAccessDomain(domainFqn: DomainFqn, username: String): Future[Boolean] = {
    (authorizationActor ? ConvergenceAuthorizedRequest(username, domainFqn, Set("domain-access"))).mapTo[Try[Boolean]].map(_.get)
  }
}
