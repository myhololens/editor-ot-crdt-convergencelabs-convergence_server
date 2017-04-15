package com.convergencelabs.server.domain.model

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration
import scala.util.Success

import org.mockito.ArgumentCaptor
import org.mockito.Matchers
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpecLike
import org.scalatest.mock.MockitoSugar

import com.convergencelabs.server.UnknownErrorResponse
import com.convergencelabs.server.datastore.domain.ModelOperationProcessor
import com.convergencelabs.server.datastore.domain.ModelSnapshotStore
import com.convergencelabs.server.datastore.domain.ModelStore
import com.convergencelabs.server.domain.DomainFqn
import com.convergencelabs.server.domain.ModelSnapshotConfig
import com.convergencelabs.server.domain.model.data.NullValue
import com.convergencelabs.server.domain.model.data.ObjectValue
import com.convergencelabs.server.domain.model.data.StringValue
import com.convergencelabs.server.domain.model.ot.ObjectAddPropertyOperation

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.testkit.TestProbe
import com.convergencelabs.server.datastore.domain.ModelPermissions
import com.convergencelabs.server.datastore.domain.ModelPermissionsStore
import com.convergencelabs.server.datastore.domain.CollectionPermissions
import com.convergencelabs.server.datastore.domain.DomainPersistenceProvider
import com.convergencelabs.server.datastore.domain.CollectionStore

// FIXME we really only check message types and not data.
// scalastyle:off magic.number
class RealtimeModelActorSpec
    extends TestKit(ActorSystem("RealtimeModelActorSpec"))
    with WordSpecLike
    with BeforeAndAfterAll
    with MockitoSugar {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "A RealtimeModelActor" when {
    "opening a closed model" must {
      "load the model from the database if it is persisted" in new MockDatabaseWithModel {
        val client = new TestProbe(system)
        realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid1, session1), Some(modelId), Some(1), client.ref), client.ref)

        val message = client.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])

        assert(message.modelData == modelData.data)
        assert(message.metaData.version == modelData.metaData.version)
        assert(message.metaData.createdTime == modelData.metaData.createdTime)
        assert(message.metaData.modifiedTime == modelData.metaData.modifiedTime)
      }

      "notify openers of an initialization errror" in new MockDatabaseWithModel {
        val client = new TestProbe(system)

        // Set the database up to bomb
        Mockito.when(modelStore.getModel(Matchers.any())).thenThrow(new IllegalArgumentException("Induced error for test"))

        realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid1, session1), Some(modelId), Some(1), client.ref), client.ref)
        val message = client.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[UnknownErrorResponse])
      }

      "ask all connecting clients for state if it is not persisted" in new MockDatabaseWithoutModel {
        val client1 = new TestProbe(system)
        val client2 = new TestProbe(system)

        realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid1, session1), Some(modelId), Some(1), client1.ref), client1.ref)
        val dataRequest1 = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ClientAutoCreateModelConfigRequest])
        assert(dataRequest1.autoConfigId == 1)

        realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid2, session2), Some(modelId), Some(1), client2.ref), client2.ref)
        val dataRequest2 = client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ClientAutoCreateModelConfigRequest])
        assert(dataRequest1.autoConfigId == 1)
      }

      "reject a client that does not respond with data" in new MockDatabaseWithoutModel {
        val client1 = new TestProbe(system)
        realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid1, session1), Some(modelId), Some(1), client1.ref), client1.ref)
        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ClientAutoCreateModelConfigRequest])
        client1.expectMsgClass(FiniteDuration(200, TimeUnit.MILLISECONDS), classOf[OpenModelFailure])
      }

      "reject a client that responds with the wrong message in request to data" in new MockDatabaseWithoutModel {
        val client1 = new TestProbe(system)
        realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid1, session1), Some(modelId), Some(1), client1.ref), client1.ref)
        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ClientAutoCreateModelConfigRequest])
        client1.reply("some object") // Any message that is not a ClientModelDataResponse will do here.
        client1.expectMsgClass(FiniteDuration(200, TimeUnit.MILLISECONDS), classOf[UnknownErrorResponse])
      }

      "notify all queued clients when data is returned by the first client" in new MockDatabaseWithoutModel {
        val client1 = new TestProbe(system)
        val client2 = new TestProbe(system)

        realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid1, session1), Some(modelId), Some(1), client1.ref), client1.ref)
        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ClientAutoCreateModelConfigRequest])

        realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid2, session2), Some(modelId), Some(1), client2.ref), client2.ref)
        client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ClientAutoCreateModelConfigRequest])

        // Now mock that the data is there.
        val now = Instant.now()
        Mockito.when(modelStore.createModel(modelFqn.collectionId, Some(modelFqn.modelId), modelJsonData, true, modelPermissions))
          .thenReturn(Success(Model(ModelMetaData(collectionId, modelId, 0L, now, now, true, modelPermissions), modelJsonData)))
        Mockito.when(modelSnapshotStore.createSnapshot(Matchers.any())).thenReturn(Success(()))
        Mockito.when(modelStore.getModel(modelId)).thenReturn(Success(Some(modelData)))
        Mockito.when(modelSnapshotStore.getLatestSnapshotMetaDataForModel(modelId)).thenReturn(Success(Some(modelSnapshotMetaData)))

        client1.reply(ClientAutoCreateModelConfigResponse(collectionId, Some(modelJsonData), Some(true), Some(modelPermissions), None))
        client2.reply(ClientAutoCreateModelConfigResponse(collectionId, Some(modelJsonData), Some(true), Some(modelPermissions), None))

        // Verify that both clients got the data.
        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])
        val openResponse = client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])

        assert(openResponse.modelData == modelJsonData)
        assert(openResponse.metaData.version == modelData.metaData.version)
        assert(openResponse.metaData.createdTime == modelData.metaData.createdTime)
        assert(openResponse.metaData.modifiedTime == modelData.metaData.modifiedTime)

        // Verify that the model and snapshot were created.
        // FIXME use arg capture to match it.
        verify(modelStore, times(1)).createModel(Matchers.any(), Matchers.any(), Matchers.any(), Matchers.any(), Matchers.any())

        val snapshotCaptor = ArgumentCaptor.forClass(classOf[ModelSnapshot])

        verify(modelSnapshotStore, times(1)).createSnapshot(snapshotCaptor.capture())
        val capturedData = snapshotCaptor.getValue
        assert(capturedData.data == modelJsonData)
        assert(capturedData.metaData.modelId == modelId)
        assert(capturedData.metaData.version == 0) // since it is newly created.
        assert(capturedData.metaData.timestamp != 0)
      }
    }

    "opening an open model" must {
      "not allow the same session to open the same model twice" in new MockDatabaseWithModel {
        val client1 = new TestProbe(system)

        realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid1, session1), Some(modelId), Some(1), client1.ref), client1.ref)
        val open1 = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])

        realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid1, session1), Some(modelId), Some(1), client1.ref), client1.ref)
        val open2 = client1.expectMsg(FiniteDuration(1, TimeUnit.SECONDS), ModelAlreadyOpen)
      }
    }

    "closing a closed a model" must {
      "acknowledge the close" in new MockDatabaseWithModel with OneOpenClient {
        realtimeModelActor.tell(CloseRealtimeModelRequest(SessionKey(uid1, session1)), client1.ref)
        val closeAck = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[CloseRealtimeModelSuccess])
      }

      "respond with an error for an invalid cId" in new MockDatabaseWithModel with OneOpenClient {
        realtimeModelActor.tell(CloseRealtimeModelRequest(SessionKey(uid1, "invalidCId")), client1.ref)
        client1.expectMsg(FiniteDuration(1, TimeUnit.SECONDS), ModelNotOpened)
      }

      "notify other connected clients" in new MockDatabaseWithModel {
        val client1 = new TestProbe(system)
        val client2 = new TestProbe(system)

        realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid1, session1), Some(modelId), Some(1), client1.ref), client1.ref)
        var client1Response = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])

        realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid2, session2), Some(modelId), Some(1), client2.ref), client2.ref)
        var client2Response = client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])

        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[RemoteClientOpened])

        realtimeModelActor.tell(CloseRealtimeModelRequest(SessionKey(uid2, session2)), client2.ref)
        val closeAck = client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[CloseRealtimeModelSuccess])

        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[RemoteClientClosed])
      }

      "notify domain when last client disconnects" in new MockDatabaseWithModel {
        val client1 = new TestProbe(system)

        realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid1, session1), Some(modelId), Some(1), client1.ref), client1.ref)
        var client1Response = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])
        realtimeModelActor.tell(CloseRealtimeModelRequest(SessionKey(uid1, session1)), client1.ref)
        val closeAck = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[CloseRealtimeModelSuccess])
        modelManagerActor.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ModelShutdownRequest])
      }
    }

    "receiving an operation" must {
      "send an ack back to the submitting client" in new OneOpenClient {
        Mockito.when(modelOperationProcessor.processModelOperation(Matchers.any())).thenReturn(Success(()))
        realtimeModelActor.tell(OperationSubmission(0L, modelData.metaData.version, ObjectAddPropertyOperation("", false, "foo", NullValue(""))), client1.ref)
        val opAck = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OperationAcknowledgement])
      }

      "send an operation to other connected clients" in new TwoOpenClients {
        Mockito.when(modelOperationProcessor.processModelOperation(Matchers.any())).thenReturn(Success(()))

        realtimeModelActor.tell(OperationSubmission(0L, modelData.metaData.version, ObjectAddPropertyOperation("", false, "foo", NullValue(""))), client1.ref)
        val opAck = client1.expectMsgClass(FiniteDuration(120, TimeUnit.SECONDS), classOf[OperationAcknowledgement])

        client2.expectMsgClass(FiniteDuration(120, TimeUnit.SECONDS), classOf[OutgoingOperation])
      }

      "close a client that submits an invalid operation" in new TwoOpenClients {
        // FIXME we don't know how to stimulate this anymore because the operation
        // gets no oped because the VID doens't exists.  Maybe we need to first
        // create a VID that is an object and then target a string at it?

        //        val badOp = StringInsertOperation("", false, 1, "bad op")
        //
        //        Mockito.when(modelOperationProcessor.processModelOperation(
        //          Matchers.any())).thenReturn(Failure(new IllegalArgumentException("Induced Exception for test: Invalid Operation")))
        //
        //        realtimeModelActor.tell(OperationSubmission(
        //          0L,
        //          modelData.metaData.version,
        //          badOp), client1.ref)
        //
        //        client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[RemoteClientClosed])
        //        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ModelForceClose])
      }
    }

    "open and a model is deleted" must {
      "force close all clients" in new TwoOpenClients {
        realtimeModelActor.tell(ModelDeleted, modelManagerActor.ref)
        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ModelForceClose])
        client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ModelForceClose])
      }
    }
  }

  trait TestFixture {
    val uid1 = "u1"
    val uid2 = "u2"

    val session1 = "s1"
    val session2 = "s2"

    val modelPermissions = ModelPermissions(true, true, true, true)

    val collectionId = "collection"
    val modelId = "model" + System.nanoTime()
    val modelFqn = ModelFqn(collectionId, modelId)
    val modelJsonData = ObjectValue("vid1", Map("key" -> StringValue("vid2", "value")))
    val modelCreateTime = Instant.ofEpochMilli(2L)
    val modelModifiedTime = Instant.ofEpochMilli(3L)
    val modelData = Model(ModelMetaData(collectionId, modelId, 1L, modelCreateTime, modelModifiedTime, true, modelPermissions), modelJsonData)
    val modelSnapshotTime = Instant.ofEpochMilli(2L)
    val modelSnapshotMetaData = ModelSnapshotMetaData(modelId, 1L, modelSnapshotTime)

    val collectionStore = mock[CollectionStore]
    val modelStore = mock[ModelStore]
    val modelOperationProcessor = mock[ModelOperationProcessor]
    val modelSnapshotStore = mock[ModelSnapshotStore]
    val modelPermissionStore = mock[ModelPermissionsStore]
    val persistenceProvider = mock[DomainPersistenceProvider]

    Mockito.when(persistenceProvider.collectionStore).thenReturn(collectionStore)
    Mockito.when(persistenceProvider.modelStore).thenReturn(modelStore)
    Mockito.when(persistenceProvider.modelSnapshotStore).thenReturn(modelSnapshotStore)
    Mockito.when(persistenceProvider.modelPermissionsStore).thenReturn(modelPermissionStore)
    Mockito.when(persistenceProvider.modelOperationProcessor).thenReturn(modelOperationProcessor)

    Mockito.when(collectionStore.getOrCreateCollection(collectionId)).thenReturn(Success(Collection(
      collectionId,
      collectionId,
      true,
      ModelSnapshotConfig(false, false, false, 0, 0, false, false, Duration.ofSeconds(0), Duration.ofSeconds(0)),
      CollectionPermissions(true, true, true, true, true))))
    Mockito.when(collectionStore.ensureCollectionExists(Matchers.any())).thenReturn(Success(()))
      
    Mockito.when(modelPermissionStore.getCollectionWorldPermissions(collectionId)).thenReturn(Success(CollectionPermissions(true, true, true, true, true)))
    Mockito.when(modelPermissionStore.getAllCollectionUserPermissions(collectionId)).thenReturn(Success(Map[String, CollectionPermissions]()))
    Mockito.when(modelPermissionStore.getCollectionUserPermissions(Matchers.any(), Matchers.any())).thenReturn(Success(None))
    Mockito.when(modelPermissionStore.updateModelUserPermissions(Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Success(()))
    Mockito.when(modelPermissionStore.updateAllModelUserPermissions(Matchers.any(), Matchers.any())).thenReturn(Success(()))
    Mockito.when(modelPermissionStore.modelOverridesCollectionPermissions(modelId)).thenReturn(Success(false))
    Mockito.when(modelPermissionStore.getModelWorldPermissions(modelId)).thenReturn(Success(ModelPermissions(true, true, true, true)))
    Mockito.when(modelPermissionStore.getAllModelUserPermissions(modelId)).thenReturn(Success(Map[String, ModelPermissions]()))
    
    val resourceId = "1" + System.nanoTime()
    val modelManagerActor = new TestProbe(system)
    val props = RealtimeModelActor.props(
      modelManagerActor.ref,
      DomainFqn("convergence", "default"),
      modelId,
      resourceId,
      persistenceProvider,
      100L)

    val realtimeModelActor = system.actorOf(props, resourceId)
  }

  trait MockDatabaseWithModel extends TestFixture {
    // FIXME: Add the rest of the persistence mocks

    Mockito.when(modelStore.modelExists(modelId)).thenReturn(Success(true))
    Mockito.when(modelStore.getModel(modelId)).thenReturn(Success(Some(modelData)))
    Mockito.when(modelSnapshotStore.getLatestSnapshotMetaDataForModel(modelId)).thenReturn(Success(Some(modelSnapshotMetaData)))
  }

  trait OneOpenClient extends MockDatabaseWithModel {
    val client1 = new TestProbe(system)
    realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid1, session1), Some(modelId), Some(1), client1.ref), client1.ref)
    val client1OpenResponse = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])
  }

  trait TwoOpenClients extends OneOpenClient {
    val client2 = new TestProbe(system)
    realtimeModelActor.tell(OpenRealtimeModelRequest(SessionKey(uid2, session1), Some(modelId), Some(1), client2.ref), client2.ref)
    val client2OpenResponse = client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])
    client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[RemoteClientOpened])
  }

  trait MockDatabaseWithoutModel extends TestFixture {
    Mockito.when(modelStore.modelExists(modelId)).thenReturn(Success(false))
  }
}
