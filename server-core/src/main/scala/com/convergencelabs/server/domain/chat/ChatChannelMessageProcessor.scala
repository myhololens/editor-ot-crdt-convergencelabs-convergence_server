package com.convergencelabs.server.domain.chat

import scala.util.Try

import com.convergencelabs.server.datastore.domain.ChatChannelInfo
import com.convergencelabs.server.datastore.domain.ChatMessageEvent
import com.convergencelabs.server.datastore.domain.ChatNameChangedEvent
import com.convergencelabs.server.datastore.domain.ChatTopicChangedEvent
import com.convergencelabs.server.datastore.domain.ChatUserAddedEvent
import com.convergencelabs.server.datastore.domain.ChatUserJoinedEvent
import com.convergencelabs.server.datastore.domain.ChatUserLeftEvent
import com.convergencelabs.server.datastore.domain.ChatUserRemovedEvent
import com.convergencelabs.server.domain.chat.ChatChannelMessages.AddUserToChannelRequest
import com.convergencelabs.server.domain.chat.ChatChannelMessages.ChannelNameChanged
import com.convergencelabs.server.domain.chat.ChatChannelMessages.ChannelTopicChanged
import com.convergencelabs.server.domain.chat.ChatChannelMessages.ExistingChannelMessage
import com.convergencelabs.server.domain.chat.ChatChannelMessages.GetChannelHistoryRequest
import com.convergencelabs.server.domain.chat.ChatChannelMessages.GetChannelHistoryResponse
import com.convergencelabs.server.domain.chat.ChatChannelMessages.JoinChannelRequest
import com.convergencelabs.server.domain.chat.ChatChannelMessages.JoinChannelResponse
import com.convergencelabs.server.domain.chat.ChatChannelMessages.LeaveChannelRequest
import com.convergencelabs.server.domain.chat.ChatChannelMessages.MarkChannelEventsSeenRequest
import com.convergencelabs.server.domain.chat.ChatChannelMessages.PublishChatMessageRequest
import com.convergencelabs.server.domain.chat.ChatChannelMessages.RemoteChatMessage
import com.convergencelabs.server.domain.chat.ChatChannelMessages.RemoveChannelRequest
import com.convergencelabs.server.domain.chat.ChatChannelMessages.RemoveUserFromChannelRequest
import com.convergencelabs.server.domain.chat.ChatChannelMessages.SetChannelNameRequest
import com.convergencelabs.server.domain.chat.ChatChannelMessages.SetChannelTopicRequest
import com.convergencelabs.server.domain.chat.ChatChannelMessages.UserAddedToChannel
import com.convergencelabs.server.domain.chat.ChatChannelMessages.UserJoinedChannel
import com.convergencelabs.server.frontend.realtime.ChatChannelRemovedMessage
import com.convergencelabs.server.domain.chat.ChatChannelMessages.AddChatPermissionsRequest
import com.convergencelabs.server.domain.chat.ChatChannelMessages.RemoveChatPermissionsRequest
import com.convergencelabs.server.domain.chat.ChatChannelMessages.SetChatPermissionsRequest

case class ChatMessageProcessingResult(response: Option[Any], broadcastMessages: List[Any])

abstract class ChatChannelMessageProcessor(stateManager: ChatChannelStateManager) {

  def processChatMessage(message: ExistingChannelMessage): Try[ChatMessageProcessingResult] = {
    message match {
      case message: RemoveChannelRequest         => onRemoveChannel(message)
      case message: JoinChannelRequest           => onJoinChannel(message)
      case message: LeaveChannelRequest          => onLeaveChannel(message)
      case message: AddUserToChannelRequest      => onAddUserToChannel(message)
      case message: RemoveUserFromChannelRequest => onRemoveUserFromChannel(message)
      case message: SetChannelNameRequest        => onSetChatChannelName(message)
      case message: SetChannelTopicRequest       => onSetChatChannelTopic(message)
      case message: MarkChannelEventsSeenRequest => onMarkEventsSeen(message)
      case message: GetChannelHistoryRequest     => onGetHistory(message)
      case message: PublishChatMessageRequest    => onPublishMessage(message)
      case message: AddChatPermissionsRequest    => onAddPermissionsMessage(message)
      case message: RemoveChatPermissionsRequest => onRemovePermissionsMessage(message)
      case message: SetChatPermissionsRequest    => onSetPermissionsMessage(message)
    }
  }

  def onJoinChannel(message: JoinChannelRequest): Try[ChatMessageProcessingResult] = {
    val JoinChannelRequest(channelId, sk, client) = message
    stateManager.onJoinChannel(sk) map {
      case ChatUserJoinedEvent(eventNo, channelId, username, timestamp) =>
        ChatMessageProcessingResult(
          Some(createJoinResponse()),
          List(UserJoinedChannel(channelId, eventNo, timestamp, username)))
    }
  }

  def onLeaveChannel(message: LeaveChannelRequest): Try[ChatMessageProcessingResult] = {
    val LeaveChannelRequest(channelId, sk, client) = message
    stateManager.onLeaveChannel(sk) map {
      case ChatUserLeftEvent(eventNo, channelId, username, timestamp) =>
        ChatMessageProcessingResult(
          Some(()),
          List(UserJoinedChannel(channelId, eventNo, timestamp, username)))
    }
  }

  def onAddUserToChannel(message: AddUserToChannelRequest): Try[ChatMessageProcessingResult] = {
    val AddUserToChannelRequest(channelId, sk, username) = message;
    stateManager.onAddUserToChannel(channelId, sk, username) map {
      case ChatUserAddedEvent(eventNo, channelId, addedBy, timestamp, username) =>
        ChatMessageProcessingResult(Some(()), List(UserAddedToChannel(channelId, eventNo, timestamp, username, addedBy)))
    }
  }

  def onRemoveUserFromChannel(message: RemoveUserFromChannelRequest): Try[ChatMessageProcessingResult] = {
    val RemoveUserFromChannelRequest(channelId, sk, username) = message;
    stateManager.onRemoveUserFromChannel(channelId, sk, username) map {
      case ChatUserRemovedEvent(eventNo, channelId, removedBy, timestamp, username) =>
        ChatMessageProcessingResult(Some(()), List(UserAddedToChannel(channelId, eventNo, timestamp, username, removedBy)))
    }
  }

  def onSetChatChannelName(message: SetChannelNameRequest): Try[ChatMessageProcessingResult] = {
    val SetChannelNameRequest(channelId, sk, name) = message;
    stateManager.onSetChatChannelName(channelId, sk, name) map {
      case ChatNameChangedEvent(eventNo, channelId, setBy, timestamp, topic) =>
        ChatMessageProcessingResult(Some(()), List(ChannelNameChanged(channelId, eventNo, timestamp, setBy, topic)))
    }
  }

  def onSetChatChannelTopic(message: SetChannelTopicRequest): Try[ChatMessageProcessingResult] = {
    val SetChannelTopicRequest(channelId, sk, topic) = message;
    stateManager.onSetChatChannelTopic(channelId, sk, topic) map {
      case ChatTopicChangedEvent(eventNo, channelId, setBy, timestamp, topic) =>
        ChatMessageProcessingResult(Some(()), List(ChannelTopicChanged(channelId, eventNo, timestamp, setBy, topic)))
    }
  }

  def onMarkEventsSeen(message: MarkChannelEventsSeenRequest): Try[ChatMessageProcessingResult] = {
    val MarkChannelEventsSeenRequest(channelId, sk, eventNumber) = message;
    stateManager.onMarkEventsSeen(channelId, sk, eventNumber) map { _ =>
      ChatMessageProcessingResult(Some(()), List())
    }
  }

  def onGetHistory(message: GetChannelHistoryRequest): Try[ChatMessageProcessingResult] = {
    val GetChannelHistoryRequest(channelId, sk, limit, offset, forward, eventFilter) = message;
    stateManager.onGetHistory(channelId, sk.uid, limit, offset, forward, eventFilter) map { events =>
      ChatMessageProcessingResult(Some(GetChannelHistoryResponse(events)), List())
    }
  }

  def onPublishMessage(message: PublishChatMessageRequest): Try[ChatMessageProcessingResult] = {
    val PublishChatMessageRequest(channelId, sk, msg) = message;
    stateManager.onPublishMessage(channelId, sk, msg) map {
      case ChatMessageEvent(eventNo, channelId, sk.uid, timestamp, msg) =>
        ChatMessageProcessingResult(Some(()), List(RemoteChatMessage(channelId, eventNo, timestamp, sk, msg)))
    }
  }

  def onRemoveChannel(message: RemoveChannelRequest): Try[ChatMessageProcessingResult] = {
    val RemoveChannelRequest(channelId, sk) = message;
    stateManager.onRemoveChannel(channelId, sk) map { _ =>
      ChatMessageProcessingResult(Some(()), List(ChatChannelRemovedMessage(channelId)))
    }
  }

  def onAddPermissionsMessage(message: AddChatPermissionsRequest): Try[ChatMessageProcessingResult] = {
    val AddChatPermissionsRequest(channelId, sk, world, user, group) = message;
    stateManager.onAddPermissions(channelId, sk, world, user, group) map { _ =>
      ChatMessageProcessingResult(Some(()), List())
    }
  }

  def onRemovePermissionsMessage(message: RemoveChatPermissionsRequest): Try[ChatMessageProcessingResult] = {
    val RemoveChatPermissionsRequest(channelId, sk, world, user, group) = message;
    stateManager.onRemovePermissions(channelId, sk, world, user, group) map { _ =>
      ChatMessageProcessingResult(Some(()), List())
    }
  }

  def onSetPermissionsMessage(message: SetChatPermissionsRequest): Try[ChatMessageProcessingResult] = {
    val SetChatPermissionsRequest(channelId, sk, world, user, group) = message;
    stateManager.onAddPermissions(channelId, sk, world, user, group) map { _ =>
      ChatMessageProcessingResult(Some(()), List())
    }
  }

  def createJoinResponse(): JoinChannelResponse = {
    val ChatChannelState(id, channelType, created, isPrivate, name, topic, lastEventTime, lastEventNo, members) = stateManager.state()
    val info = ChatChannelInfo(id, channelType, created, isPrivate, name, topic, members, lastEventNo, lastEventTime)
    JoinChannelResponse(info)
  }

  def boradcast(message: Any): Unit
}
