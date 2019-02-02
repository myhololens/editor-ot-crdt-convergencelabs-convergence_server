package com.convergencelabs.server.frontend.realtime

import java.time.Instant

import scala.language.implicitConversions

import com.convergencelabs.server.datastore.domain.ChatChannelEvent
import com.convergencelabs.server.datastore.domain.ChatChannelInfo
import com.convergencelabs.server.datastore.domain.ChatCreatedEvent
import com.convergencelabs.server.datastore.domain.ChatMessageEvent
import com.convergencelabs.server.datastore.domain.ChatNameChangedEvent
import com.convergencelabs.server.datastore.domain.ChatTopicChangedEvent
import com.convergencelabs.server.datastore.domain.ChatUserAddedEvent
import com.convergencelabs.server.datastore.domain.ChatUserJoinedEvent
import com.convergencelabs.server.datastore.domain.ChatUserLeftEvent
import com.convergencelabs.server.datastore.domain.ChatUserRemovedEvent
import com.convergencelabs.server.datastore.domain.ModelPermissions
import com.convergencelabs.server.domain.DomainUser
import com.convergencelabs.server.domain.model.data.ArrayValue
import com.convergencelabs.server.domain.model.data.BooleanValue
import com.convergencelabs.server.domain.model.data.DataValue
import com.convergencelabs.server.domain.model.data.DateValue
import com.convergencelabs.server.domain.model.data.DoubleValue
import com.convergencelabs.server.domain.model.data.NullValue
import com.convergencelabs.server.domain.model.data.ObjectValue
import com.convergencelabs.server.domain.model.data.StringValue
import com.convergencelabs.server.domain.presence.UserPresence
import com.google.protobuf.timestamp.Timestamp

import io.convergence.proto.chat.ChatChannelEventData
import io.convergence.proto.chat.ChatChannelMemberData
import io.convergence.proto.chat.ChatCreatedEventData
import io.convergence.proto.chat.ChatMessageEventData
import io.convergence.proto.chat.ChatNameChangedEventData
import io.convergence.proto.chat.ChatTopicChangedEventData
import io.convergence.proto.chat.ChatUserAddedEventData
import io.convergence.proto.chat.ChatUserJoinedEventData
import io.convergence.proto.chat.ChatUserLeftEventData
import io.convergence.proto.chat.ChatUserRemovedEventData
import io.convergence.proto.identity.DomainUserData
import io.convergence.proto.model.ModelPermissionsData
import com.convergencelabs.server.domain.DomainUserId
import com.convergencelabs.server.domain.DomainUserType
import io.convergence.proto.identity.DomainUserTypeData
import io.convergence.proto.identity.DomainUserIdData
import io.convergence.proto.model.UserModelPermissionsEntry

object ImplicitMessageConversions {
  implicit def instanceToTimestamp(instant: Instant) = Timestamp(instant.getEpochSecond, instant.getNano)
  implicit def timestampToInstant(timestamp: Timestamp) = Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos)

  implicit def dataValueToMessage(dataValue: DataValue): io.convergence.proto.operations.DataValue =
    dataValue match {
      case value: ObjectValue => io.convergence.proto.operations.DataValue().withObjectValue(objectValueToMessage(value))
      case value: ArrayValue => io.convergence.proto.operations.DataValue().withArrayValue(arrayValueToMessage(value))
      case value: BooleanValue => io.convergence.proto.operations.DataValue().withBooleanValue(booleanValueToMessage(value))
      case value: DoubleValue => io.convergence.proto.operations.DataValue().withDoubleValue(doubleValueToMessage(value))
      case value: NullValue => io.convergence.proto.operations.DataValue().withNullValue(nullValueToMessage(value))
      case value: StringValue => io.convergence.proto.operations.DataValue().withStringValue(stringValueToMessage(value))
      case value: DateValue => io.convergence.proto.operations.DataValue().withDateValue(dateValueToMessage(value))
    }

  implicit def objectValueToMessage(objectValue: ObjectValue) =
    io.convergence.proto.operations.ObjectValue(
      objectValue.id,
      objectValue.children map {
        case (key, value) => (key, dataValueToMessage(value))
      })

  implicit def arrayValueToMessage(arrayValue: ArrayValue) = io.convergence.proto.operations.ArrayValue(arrayValue.id, arrayValue.children.map(dataValueToMessage))
  implicit def booleanValueToMessage(booleanValue: BooleanValue) = io.convergence.proto.operations.BooleanValue(booleanValue.id, booleanValue.value)
  implicit def doubleValueToMessage(doubleValue: DoubleValue) = io.convergence.proto.operations.DoubleValue(doubleValue.id, doubleValue.value)
  implicit def nullValueToMessage(nullValue: NullValue) = io.convergence.proto.operations.NullValue(nullValue.id)
  implicit def stringValueToMessage(stringValue: StringValue) = io.convergence.proto.operations.StringValue(stringValue.id, stringValue.value)
  implicit def dateValueToMessage(dateValue: DateValue) = io.convergence.proto.operations.DateValue(dateValue.id, Some(instanceToTimestamp(dateValue.value)))

  implicit def messageToDataValue(dataValue: io.convergence.proto.operations.DataValue): DataValue =
    dataValue.value match {
      case io.convergence.proto.operations.DataValue.Value.ObjectValue(value) => messageToObjectValue(value)
      case io.convergence.proto.operations.DataValue.Value.ArrayValue(value) => messageToArrayValue(value)
      case io.convergence.proto.operations.DataValue.Value.BooleanValue(value) => messageToBooleanValue(value)
      case io.convergence.proto.operations.DataValue.Value.DoubleValue(value) => messageToDoubleValue(value)
      case io.convergence.proto.operations.DataValue.Value.NullValue(value) => messageToNullValue(value)
      case io.convergence.proto.operations.DataValue.Value.StringValue(value) => messageToStringValue(value)
      case io.convergence.proto.operations.DataValue.Value.DateValue(value) => messageToDateValue(value)
      case io.convergence.proto.operations.DataValue.Value.Empty => ???
    }

  implicit def messageToObjectValue(objectValue: io.convergence.proto.operations.ObjectValue) =
    ObjectValue(
      objectValue.id,
      objectValue.children map {
        case (key, value) => (key, messageToDataValue(value))
      })

  implicit def messageToArrayValue(arrayValue: io.convergence.proto.operations.ArrayValue) = ArrayValue(arrayValue.id, arrayValue.children.map(messageToDataValue).toList)
  implicit def messageToBooleanValue(booleanValue: io.convergence.proto.operations.BooleanValue) = BooleanValue(booleanValue.id, booleanValue.value)
  implicit def messageToDoubleValue(doubleValue: io.convergence.proto.operations.DoubleValue) = DoubleValue(doubleValue.id, doubleValue.value)
  implicit def messageToNullValue(nullValue: io.convergence.proto.operations.NullValue) = NullValue(nullValue.id)
  implicit def messageToStringValue(stringValue: io.convergence.proto.operations.StringValue) = StringValue(stringValue.id, stringValue.value)
  implicit def messageToDateValue(dateValue: io.convergence.proto.operations.DateValue) = DateValue(dateValue.id, timestampToInstant(dateValue.value.get))

  implicit def channelInfoToMessage(info: ChatChannelInfo) =
    io.convergence.proto.chat.ChatChannelInfoData(
      info.id, info.channelType,
      info.isPrivate match {
        case true => "private"
        case false => "public"
      },
      info.name,
      info.topic,
      Some(info.created),
      Some(info.lastEventTime),
      info.lastEventNumber,
      info.members.map(member => ChatChannelMemberData(Some(member.userId), member.seen)).toSeq)

  implicit def channelEventToMessage(event: ChatChannelEvent): ChatChannelEventData = event match {
    case ChatCreatedEvent(eventNumber, channel, user, timestamp, name, topic, members) =>
      ChatChannelEventData().withCreated(
        ChatCreatedEventData(channel, eventNumber, Some(timestamp), Some(user), name, topic, members.map(ImplicitMessageConversions.domainUserIdToData(_)).toSeq));
    case ChatMessageEvent(eventNumber, channel, user, timestamp, message) =>
      ChatChannelEventData().withMessage(
        ChatMessageEventData(channel, eventNumber, Some(timestamp), Some(user), message))
    case ChatUserJoinedEvent(eventNumber, channel, user, timestamp) =>
      ChatChannelEventData().withUserJoined(
        ChatUserJoinedEventData(channel, eventNumber, Some(timestamp), Some(user)))
    case ChatUserLeftEvent(eventNumber, channel, user, timestamp) =>
      ChatChannelEventData().withUserLeft(
        ChatUserLeftEventData(channel, eventNumber, Some(timestamp), Some(user)))
    case ChatUserAddedEvent(eventNumber, channel, user, timestamp, addedUser) =>
      ChatChannelEventData().withUserAdded(
        ChatUserAddedEventData(channel, eventNumber, Some(timestamp), Some(user), Some(addedUser)))
    case ChatUserRemovedEvent(eventNumber, channel, user, timestamp, removedUser) =>
      ChatChannelEventData().withUserRemoved(
        ChatUserRemovedEventData(channel, eventNumber, Some(timestamp), Some(removedUser)))
    case ChatNameChangedEvent(eventNumber, channel, user, timestamp, name) =>
      ChatChannelEventData().withNameChanged(
        ChatNameChangedEventData(channel, eventNumber, Some(timestamp), Some(user), name))
    case ChatTopicChangedEvent(eventNumber, channel, user, timestamp, topic) =>
      ChatChannelEventData().withTopicChanged(
        ChatTopicChangedEventData(channel, eventNumber, Some(timestamp), Some(user), topic))
  }

  implicit def modelPermissionsToMessage(permissions: ModelPermissions) =
    ModelPermissionsData(permissions.read, permissions.write, permissions.remove, permissions.manage)

  implicit def userPresenceToMessage(userPresence: UserPresence) =
    io.convergence.proto.presence.UserPresence(
      Some(domainUserIdToData(userPresence.userId)),
      userPresence.available,
      JsonProtoConverter.jValueMapToValueMap(userPresence.state))

  def mapDomainUser(user: DomainUser): DomainUserData = {
    val DomainUser(userType, username, firstname, lastName, displayName, email, disabled, deleted, deletedUsername) = user
    val userId = DomainUserId(userType, username)
    val userIdData = Some(domainUserIdToData(userId))
    DomainUserData(userIdData, firstname, lastName, displayName, email, disabled, deleted, deletedUsername)
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

  implicit def modelUserPermissionSeqToMap(entries: Seq[UserModelPermissionsEntry]): Map[DomainUserId, ModelPermissions] = {
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

  implicit def modelUserPermissionSeqToMap(permissionMap: Map[DomainUserId, ModelPermissions]): Seq[UserModelPermissionsEntry] = {
    val mapped = permissionMap.map {
      case (user, ModelPermissions(read, write, remove, manage)) =>
        (user, UserModelPermissionsEntry(Some(user), Some(ModelPermissionsData(read, write, remove, manage))))
    }
    
    mapped.values.toSeq
  }
}
