package com.convergencelabs.server.datastore

import com.convergencelabs.server.datastore.UserStoreActor.DeleteDomainUser
import com.convergencelabs.server.datastore.UserStoreActor.GetUserByUsername
import com.convergencelabs.server.datastore.UserStoreActor.SetPassword
import com.convergencelabs.server.datastore.UserStoreActor.UpdateUser
import com.convergencelabs.server.datastore.domain.DomainUserStore
import com.convergencelabs.server.datastore.domain.DomainUserStore.CreateNormalDomainUser
import com.convergencelabs.server.datastore.domain.DomainUserStore.UpdateDomainUser

import UserStoreActor.CreateUser
import akka.actor.ActorLogging
import akka.actor.Props
import scala.util.Success
import com.convergencelabs.server.datastore.UserStoreActor.GetUsers
import com.convergencelabs.server.datastore.domain.DomainUserField
import com.convergencelabs.server.domain.model.query.Ast.OrderBy

object UserStoreActor {
  def props(userStore: DomainUserStore): Props = Props(new UserStoreActor(userStore))

  trait UserStoreRequest
  case class GetUsers(
    filter: Option[String],
    limit: Option[Int],
    offset: Option[Int]) extends UserStoreRequest
  case class GetUserByUsername(username: String) extends UserStoreRequest
  case class CreateUser(
    username: String,
    firstName: Option[String],
    lastName: Option[String],
    displayName: Option[String],
    email: Option[String],
    password: Option[String]) extends UserStoreRequest
  case class DeleteDomainUser(uid: String) extends UserStoreRequest
  case class UpdateUser(
    username: String,
    firstName: Option[String],
    lastName: Option[String],
    displayName: Option[String],
    email: Option[String]) extends UserStoreRequest
  case class SetPassword(
    uid: String,
    password: String) extends UserStoreRequest
}

class UserStoreActor private[datastore] (private[this] val userStore: DomainUserStore)
    extends StoreActor with ActorLogging {

  def receive: Receive = {
    case message: GetUserByUsername => getUserByUsername(message)
    case message: CreateUser        => createUser(message)
    case message: DeleteDomainUser  => deleteUser(message)
    case message: UpdateUser        => updateUser(message)
    case message: SetPassword       => setPassword(message)
    case message: GetUsers          => getAllUsers(message)
    case message: Any               => unhandled(message)
  }

  def getAllUsers(message: GetUsers): Unit = {
    val GetUsers(filter, limit, offset) = message
    filter match {
      case Some(filterString) =>
        reply(userStore.searchUsersByFields(
          List(DomainUserField.Username, DomainUserField.Email),
          filterString,
          Some(DomainUserField.Username),
          Some(SortOrder.Ascending),
          limit,
          offset))
      case None =>
        reply(userStore.getAllDomainUsers(Some(DomainUserField.Username), Some(SortOrder.Ascending), limit, offset))
    }
  }

  def createUser(message: CreateUser): Unit = {
    val CreateUser(username, firstName, lastName, displayName, email, password) = message
    val domainuser = CreateNormalDomainUser(username, firstName, lastName, displayName, email)
    val result = userStore.createNormalDomainUser(domainuser) flatMap { createResult =>
      // FIXME this only works as a hack because of the way our create result works.
      password match {
        case None =>
          Success(createResult)
        case Some(pw) =>
          userStore.setDomainUserPassword(username, pw) map { _ =>
            createResult
          }
      }
    }
    reply(result)
  }

  def updateUser(message: UpdateUser): Unit = {
    val UpdateUser(username, firstName, lastName, displayName, email) = message
    val domainuser = UpdateDomainUser(username, firstName, lastName, displayName, email);
    reply(userStore.updateDomainUser(domainuser))
  }

  def setPassword(message: SetPassword): Unit = {
    val SetPassword(uid, password) = message
    reply(userStore.setDomainUserPassword(uid, password))
  }

  def deleteUser(message: DeleteDomainUser): Unit = {
    reply(userStore.deleteDomainUser(message.uid))
  }

  def getUserByUsername(message: GetUserByUsername): Unit = {
    reply(userStore.getDomainUserByUsername(message.username))
  }
}
