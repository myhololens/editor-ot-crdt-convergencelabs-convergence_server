/*
 * Copyright (c) 2019 - Convergence Labs, Inc.
 *
 * This file is part of the Convergence Server, which is released under
 * the terms of the GNU General Public License version 3 (GPLv3). A copy
 * of the GPLv3 should have been provided along with this file, typically
 * located in the "LICENSE" file, which is part of this source code package.
 * Alternatively, see <https://www.gnu.org/licenses/gpl-3.0.html> for the
 * full text of the GPLv3 license, if it was not provided.
 */

package com.convergencelabs.convergence.server.api.realtime

import java.time.Instant

import com.convergencelabs.convergence.proto.chat._
import com.convergencelabs.convergence.proto.core._
import com.convergencelabs.convergence.proto.model
import com.convergencelabs.convergence.proto.model._
import com.convergencelabs.convergence.proto.presence.UserPresenceData
import com.convergencelabs.convergence.server.datastore.domain._
import com.convergencelabs.convergence.server.domain.{DomainUser, DomainUserId, DomainUserType}
import com.convergencelabs.convergence.server.domain.model.data.{ArrayValue, BooleanValue, DataValue, DateValue, DoubleValue, NullValue, ObjectValue, StringValue}
import com.convergencelabs.convergence.server.domain.presence.UserPresence
import com.google.protobuf.timestamp.Timestamp

import scala.language.implicitConversions


object ImplicitMessageConversions {
  implicit def instanceToTimestamp(instant: Instant): Timestamp = Timestamp(instant.getEpochSecond, instant.getNano)
  implicit def timestampToInstant(timestamp: Timestamp): Instant = Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos)

  implicit def dataValueToMessage(dataValue: DataValue): com.convergencelabs.convergence.proto.model.DataValue =
    dataValue match {
      case value: ObjectValue => com.convergencelabs.convergence.proto.model.DataValue().withObjectValue(objectValueToMessage(value))
      case value: ArrayValue => com.convergencelabs.convergence.proto.model.DataValue().withArrayValue(arrayValueToMessage(value))
      case value: BooleanValue => com.convergencelabs.convergence.proto.model.DataValue().withBooleanValue(booleanValueToMessage(value))
      case value: DoubleValue => com.convergencelabs.convergence.proto.model.DataValue().withDoubleValue(doubleValueToMessage(value))
      case value: NullValue => com.convergencelabs.convergence.proto.model.DataValue().withNullValue(nullValueToMessage(value))
      case value: StringValue => com.convergencelabs.convergence.proto.model.DataValue().withStringValue(stringValueToMessage(value))
      case value: DateValue => com.convergencelabs.convergence.proto.model.DataValue().withDateValue(dateValueToMessage(value))
    }

  implicit def objectValueToMessage(objectValue: ObjectValue): model.ObjectValue =
    com.convergencelabs.convergence.proto.model.ObjectValue(
      objectValue.id,
      objectValue.children map {
        case (key, value) => (key, dataValueToMessage(value))
      })

  implicit def arrayValueToMessage(arrayValue: ArrayValue): model.ArrayValue = com.convergencelabs.convergence.proto.model.ArrayValue(arrayValue.id, arrayValue.children.map(dataValueToMessage))
  implicit def booleanValueToMessage(booleanValue: BooleanValue): model.BooleanValue = com.convergencelabs.convergence.proto.model.BooleanValue(booleanValue.id, booleanValue.value)
  implicit def doubleValueToMessage(doubleValue: DoubleValue): model.DoubleValue = com.convergencelabs.convergence.proto.model.DoubleValue(doubleValue.id, doubleValue.value)
  implicit def nullValueToMessage(nullValue: NullValue): model.NullValue = com.convergencelabs.convergence.proto.model.NullValue(nullValue.id)
  implicit def stringValueToMessage(stringValue: StringValue): model.StringValue = com.convergencelabs.convergence.proto.model.StringValue(stringValue.id, stringValue.value)
  implicit def dateValueToMessage(dateValue: DateValue): model.DateValue = com.convergencelabs.convergence.proto.model.DateValue(dateValue.id, Some(instanceToTimestamp(dateValue.value)))

  implicit def messageToDataValue(dataValue: com.convergencelabs.convergence.proto.model.DataValue): DataValue =
    dataValue.value match {
      case com.convergencelabs.convergence.proto.model.DataValue.Value.ObjectValue(value) => messageToObjectValue(value)
      case com.convergencelabs.convergence.proto.model.DataValue.Value.ArrayValue(value) => messageToArrayValue(value)
      case com.convergencelabs.convergence.proto.model.DataValue.Value.BooleanValue(value) => messageToBooleanValue(value)
      case com.convergencelabs.convergence.proto.model.DataValue.Value.DoubleValue(value) => messageToDoubleValue(value)
      case com.convergencelabs.convergence.proto.model.DataValue.Value.NullValue(value) => messageToNullValue(value)
      case com.convergencelabs.convergence.proto.model.DataValue.Value.StringValue(value) => messageToStringValue(value)
      case com.convergencelabs.convergence.proto.model.DataValue.Value.DateValue(value) => messageToDateValue(value)
      case com.convergencelabs.convergence.proto.model.DataValue.Value.Empty => ???
    }

  implicit def messageToObjectValue(objectValue: com.convergencelabs.convergence.proto.model.ObjectValue): ObjectValue =
    ObjectValue(
      objectValue.id,
      objectValue.children map {
        case (key, value) => (key, messageToDataValue(value))
      })

  implicit def messageToArrayValue(arrayValue: com.convergencelabs.convergence.proto.model.ArrayValue): ArrayValue = ArrayValue(arrayValue.id, arrayValue.children.map(messageToDataValue).toList)
  implicit def messageToBooleanValue(booleanValue: com.convergencelabs.convergence.proto.model.BooleanValue): BooleanValue = BooleanValue(booleanValue.id, booleanValue.value)
  implicit def messageToDoubleValue(doubleValue: com.convergencelabs.convergence.proto.model.DoubleValue): DoubleValue = DoubleValue(doubleValue.id, doubleValue.value)
  implicit def messageToNullValue(nullValue: com.convergencelabs.convergence.proto.model.NullValue): NullValue = NullValue(nullValue.id)
  implicit def messageToStringValue(stringValue: com.convergencelabs.convergence.proto.model.StringValue): StringValue = StringValue(stringValue.id, stringValue.value)
  implicit def messageToDateValue(dateValue: com.convergencelabs.convergence.proto.model.DateValue): DateValue = DateValue(dateValue.id, timestampToInstant(dateValue.value.get))

  implicit def channelInfoToMessage(info: ChatInfo): ChatInfoData =
    com.convergencelabs.convergence.proto.chat.ChatInfoData(
      info.id, 
      info.chatType.toString.toLowerCase(),
      info.membership.toString.toLowerCase(),
      info.name,
      info.topic,
      Some(info.created),
      Some(info.lastEventTime),
      info.lastEventNumber,
      info.members.map(member => ChatMemberData(Some(member.userId), member.seen)).toSeq)

  implicit def channelEventToMessage(event: ChatEvent): ChatEventData = event match {
    case ChatCreatedEvent(eventNumber, channel, user, timestamp, name, topic, members) =>
      ChatEventData().withCreated(
        ChatCreatedEventData(channel, eventNumber, Some(timestamp), Some(user), name, topic, members.map(ImplicitMessageConversions.domainUserIdToData).toSeq));
    case ChatMessageEvent(eventNumber, channel, user, timestamp, message) =>
      ChatEventData().withMessage(
        ChatMessageEventData(channel, eventNumber, Some(timestamp), Some(user), message))
    case ChatUserJoinedEvent(eventNumber, channel, user, timestamp) =>
      ChatEventData().withUserJoined(
        ChatUserJoinedEventData(channel, eventNumber, Some(timestamp), Some(user)))
    case ChatUserLeftEvent(eventNumber, channel, user, timestamp) =>
      ChatEventData().withUserLeft(
        ChatUserLeftEventData(channel, eventNumber, Some(timestamp), Some(user)))
    case ChatUserAddedEvent(eventNumber, channel, user, timestamp, addedUser) =>
      ChatEventData().withUserAdded(
        ChatUserAddedEventData(channel, eventNumber, Some(timestamp), Some(user), Some(addedUser)))
    case ChatUserRemovedEvent(eventNumber, channel, user, timestamp, removedUser) =>
      ChatEventData().withUserRemoved(
        ChatUserRemovedEventData(channel, eventNumber, Some(timestamp), Some(removedUser)))
    case ChatNameChangedEvent(eventNumber, channel, user, timestamp, name) =>
      ChatEventData().withNameChanged(
        ChatNameChangedEventData(channel, eventNumber, Some(timestamp), Some(user), name))
    case ChatTopicChangedEvent(eventNumber, channel, user, timestamp, topic) =>
      ChatEventData().withTopicChanged(
        ChatTopicChangedEventData(channel, eventNumber, Some(timestamp), Some(user), topic))
  }

  implicit def modelPermissionsToMessage(permissions: ModelPermissions): ModelPermissionsData =
    ModelPermissionsData(permissions.read, permissions.write, permissions.remove, permissions.manage)

  implicit def userPresenceToMessage(userPresence: UserPresence): UserPresenceData =
    com.convergencelabs.convergence.proto.presence.UserPresenceData(
      Some(domainUserIdToData(userPresence.userId)),
      userPresence.available,
      JsonProtoConverter.jValueMapToValueMap(userPresence.state))

  def mapDomainUser(user: DomainUser): DomainUserData = {
    val DomainUser(userType, username, firstName, lastName, displayName, email, _, disabled, deleted, deletedUsername) = user
    val userId = DomainUserId(userType, username)
    val userIdData = Some(domainUserIdToData(userId))
    DomainUserData(userIdData, firstName, lastName, displayName, email, disabled, deleted, deletedUsername)
  }

  implicit def domainUserIdToData(userId: DomainUserId): DomainUserIdData = {
    val DomainUserId(userType, username) = userId

    val userTypeData = userType match {
      case DomainUserType.Normal => DomainUserTypeData.Normal
      case DomainUserType.Convergence => DomainUserTypeData.Convergence
      case DomainUserType.Anonymous => DomainUserTypeData.Anonymous
    }

    DomainUserIdData(userTypeData, username)
  }

  implicit def dataToDomainUserId(userIdData: DomainUserIdData): DomainUserId = {
    val DomainUserIdData(userTypeData, username) = userIdData

    val userType = userTypeData match {
      case DomainUserTypeData.Normal => DomainUserType.Normal
      case DomainUserTypeData.Convergence => DomainUserType.Convergence
      case DomainUserTypeData.Anonymous => DomainUserType.Anonymous
      case _ => ???
    }

    DomainUserId(userType, username)
  }

  implicit def modelUserPermissionSeqToMap(entries: Seq[UserModelPermissionsData]): Map[DomainUserId, ModelPermissions] = {
    entries.map { enrty =>
      (
        ImplicitMessageConversions.dataToDomainUserId(enrty.user.get),
        ModelPermissions(
          enrty.permissions.get.read,
          enrty.permissions.get.write,
          enrty.permissions.get.remove,
          enrty.permissions.get.manage))
    }.toMap
  }

  implicit def modelUserPermissionSeqToMap(permissionMap: Map[DomainUserId, ModelPermissions]): Seq[UserModelPermissionsData] = {
    val mapped = permissionMap.map {
      case (user, ModelPermissions(read, write, remove, manage)) =>
        (user, UserModelPermissionsData(Some(user), Some(ModelPermissionsData(read, write, remove, manage))))
    }
    
    mapped.values.toSeq
  }
}
