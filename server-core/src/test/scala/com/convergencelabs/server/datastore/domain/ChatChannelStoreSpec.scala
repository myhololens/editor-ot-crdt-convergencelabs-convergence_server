package com.convergencelabs.server.datastore.domain

import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import org.scalatest.OptionValues._

import com.convergencelabs.server.db.DatabaseProvider
import com.convergencelabs.server.datastore.domain.ChatChannelStore.ChannelType
import com.convergencelabs.server.db.schema.DeltaCategory
import com.convergencelabs.server.domain.DomainUser
import com.convergencelabs.server.domain.DomainUserType
import com.orientechnologies.orient.core.storage.ORecordDuplicatedException
import com.convergencelabs.server.datastore.EntityNotFoundException
import java.time.Instant
import com.convergencelabs.server.datastore.DuplicateValueException
import com.convergencelabs.server.domain.DomainUserId

class ChatChannelStoreSpec
  extends PersistenceStoreSpec[DomainPersistenceProvider](DeltaCategory.Domain)
  with WordSpecLike
  with Matchers {

  val user1 = "user1"
  val user1Id = DomainUserId(DomainUserType.Normal, user1)

  val user2 = "user2"
  val user2Id = DomainUserId(DomainUserType.Normal, user2)

  val user3 = "user3"
  val user3Id = DomainUserId(DomainUserType.Normal, user3)

  val channel1Id = "channel1"
  val firstId = "#1"

  def createStore(dbProvider: DatabaseProvider): DomainPersistenceProvider = new DomainPersistenceProviderImpl(dbProvider)

  "A ChatChannelStore" when {
    "creating a chat channel" must {
      "return the id if provided" in withTestData { provider =>
        val id = provider.chatChannelStore.createChatChannel(
          Some(channel1Id), ChannelType.Direct, Instant.now(), false, "", "", Some(Set(user1Id, user1Id)), user1Id).get
        id shouldEqual channel1Id
      }

      "return a generated id if none is provided" in withTestData { provider =>
        val id = provider.chatChannelStore.createChatChannel(
          None, ChannelType.Direct, Instant.now(), false, "", "", None, user1Id).get
        id shouldEqual firstId
      }

      "throw exception if id is duplicate" in withTestData { provider =>
        provider.chatChannelStore.createChatChannel(
          Some(channel1Id), ChannelType.Direct, Instant.now(), false, "", "", None, user1Id).get
        an[DuplicateValueException] should be thrownBy provider.chatChannelStore.createChatChannel(
          Some(channel1Id), ChannelType.Direct, Instant.now(), false, "", "", None, user1Id).get
      }

      "not create channel if members are invalid" in withTestData { provider =>

        an[EntityNotFoundException] should be thrownBy {
          provider.chatChannelStore.createChatChannel(
            Some(channel1Id),
            ChannelType.Direct,
            Instant.now(),
            false,
            "",
            "",
            Some(Set(user1Id, DomainUserId(DomainUserType.Normal, "does_not_exist"))),
            user1Id).get
        }

        an[EntityNotFoundException] should be thrownBy { provider.chatChannelStore.getChatChannel("does_not_exist").get }
      }
    }

    "getting a chat channel" must {
      "return chat channel for valid id" in withTestData { provider =>
        val id = provider.chatChannelStore.createChatChannel(
          Some(channel1Id), ChannelType.Direct, Instant.now(), false, "testName", "testTopic", Some(Set(user1Id, user2Id)), user1Id).get
        val chatChannel = provider.chatChannelStore.getChatChannel(id).get
        chatChannel.id shouldEqual id
        chatChannel.name shouldEqual "testName"
        chatChannel.topic shouldEqual "testTopic"
        chatChannel.channelType shouldEqual "direct"
      }

      "throw error for invalid id" in withTestData { provider =>
        an[EntityNotFoundException] should be thrownBy provider.chatChannelStore.getChatChannel("does_not_exist").get
      }
    }

    "getting a chat channel info" must {
      "return chat channel for valid id" in withTestData { provider =>
        val name = "testName"
        val topic = "testTopic"
        val members = Set(user1Id, user2Id)
        val timestamp = Instant.now()

        val id = provider.chatChannelStore.createChatChannel(
          Some(channel1Id), ChannelType.Direct, timestamp, false, name, topic, Some(members), user1Id).get

        val chatChannelInfo = provider.chatChannelStore.getChatChannelInfo(id).get
        chatChannelInfo.id shouldEqual id
        chatChannelInfo.name shouldEqual "testName"
        chatChannelInfo.topic shouldEqual "testTopic"
        chatChannelInfo.channelType shouldEqual "direct"
        chatChannelInfo.lastEventNumber shouldEqual 0L
        chatChannelInfo.lastEventTime shouldEqual timestamp
        chatChannelInfo.members shouldEqual Set(
          ChatChannelMember(channel1Id, user1Id, 0),
          ChatChannelMember(channel1Id, user2Id, 0))
      }

      "return the correct max event no" in withTestData { provider =>
        val name = "testName"
        val topic = "testTopic"
        val members = Set(user1Id)
        val timestamp = Instant.now()

        val id = provider.chatChannelStore.createChatChannel(
          Some(channel1Id), ChannelType.Group, timestamp, false, name, topic, Some(members), user1Id).get

        provider.chatChannelStore.addChatMessageEvent(ChatMessageEvent(1, id, user1Id, timestamp, "foo"))
        val chatChannelInfo = provider.chatChannelStore.getChatChannelInfo(id).get

        chatChannelInfo.id shouldEqual id
        chatChannelInfo.name shouldEqual "testName"
        chatChannelInfo.topic shouldEqual "testTopic"
        chatChannelInfo.channelType shouldEqual "group"
        chatChannelInfo.lastEventNumber shouldEqual 1L
        chatChannelInfo.lastEventTime shouldEqual timestamp
        chatChannelInfo.members shouldEqual Set(ChatChannelMember(channel1Id, user1Id, 0))
      }

      "throw error for invalid id" in withTestData { provider =>
        an[EntityNotFoundException] should be thrownBy provider.chatChannelStore.getChatChannelInfo("does_not_exist").get
      }
    }

    "getting chat channel members" must {
      "return the correct users after a create" in withTestData { provider =>
        val id = provider.chatChannelStore.createChatChannel(
          Some(channel1Id), ChannelType.Direct, Instant.now(), false, "testName", "testTopic", Some(Set(user1Id, user2Id)), user1Id).get
        val members = provider.chatChannelStore.getChatChannelMembers(channel1Id).get
        members shouldEqual Set(user1Id, user2Id)
      }
    }

    "getting a direct chat channel by its members" must {
      "return the correct chat channel" in withTestData { provider =>
        val members = Set(user1Id, user2Id)
        val id = provider.chatChannelStore.createChatChannel(
          Some(channel1Id), ChannelType.Direct, Instant.now(), false, "testName", "testTopic", Some(members), user1Id).get
        val info = provider.chatChannelStore.getDirectChatChannelInfoByUsers(members).get.value
        info.id shouldBe id
      }
    }

    "getting joined channels" must {
      "return the correct chat channels" in withTestData { provider =>
        val id1 = provider.chatChannelStore.createChatChannel(
          None, ChannelType.Group, Instant.now(), false, "test1", "testTopic", Some(Set(user1Id, user2Id)), user1Id).get
        val id2 = provider.chatChannelStore.createChatChannel(
          None, ChannelType.Group, Instant.now(), false, "test2", "testTopic", Some(Set(user1Id, user3Id)), user1Id).get
        val id3 = provider.chatChannelStore.createChatChannel(
          None, ChannelType.Group, Instant.now(), false, "test3", "testTopic", Some(Set(user2Id, user3Id)), user1Id).get

        val joined = provider.chatChannelStore.getJoinedChannels(user1Id).get

        joined.map(i => i.id).toSet shouldBe Set(id1, id2)
      }
    }

    "creating chat channel events" must {
      "successfully create all chat events" in withTestData { provider =>
        val id = provider.chatChannelStore.createChatChannel(
          Some(channel1Id), ChannelType.Direct, Instant.now(), false, "testName", "testTopic", Some(Set(user1Id, user2Id)), user1Id).get
        provider.chatChannelStore.addChatMessageEvent(ChatMessageEvent(1, id, user2Id, Instant.now(), "some message")).get
        provider.chatChannelStore.addChatNameChangedEvent(ChatNameChangedEvent(2, id, user2Id, Instant.now(), "new name")).get
        provider.chatChannelStore.addChatTopicChangedEvent(ChatTopicChangedEvent(3, id, user2Id, Instant.now(), "new topic")).get
        provider.chatChannelStore.addChatUserLeftEvent(ChatUserLeftEvent(4, id, user3Id, Instant.now())).get
        provider.chatChannelStore.addChatUserJoinedEvent(ChatUserJoinedEvent(5, id, user3Id, Instant.now())).get
        provider.chatChannelStore.addChatUserRemovedEvent(ChatUserRemovedEvent(6, id, user2Id, Instant.now(), user1Id)).get
        provider.chatChannelStore.addChatUserAddedEvent(ChatUserAddedEvent(7, id, user2Id, Instant.now(), user1Id)).get
        val events = provider.chatChannelStore.getChatChannelEvents(id, None, None, None, None).get
        events.size shouldEqual 8
      }
    }
  }

  def withTestData(testCode: DomainPersistenceProvider => Any): Unit = {
    this.withPersistenceStore { provider =>
      provider.userStore.createDomainUser(DomainUser(user1Id, None, None, None, None)).get
      provider.userStore.createDomainUser(DomainUser(user2Id, None, None, None, None)).get
      provider.userStore.createDomainUser(DomainUser(user3Id, None, None, None, None)).get
      testCode(provider)
    }
  }
}
