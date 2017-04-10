package com.convergencelabs.server.domain.model

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.convergencelabs.server.ProtocolConfiguration
import com.convergencelabs.server.UnknownErrorResponse
import com.convergencelabs.server.datastore.DuplicateValueExcpetion
import com.convergencelabs.server.datastore.InvalidValueExcpetion
import com.convergencelabs.server.datastore.UnauthorizedException
import com.convergencelabs.server.datastore.domain.CollectionPermissions
import com.convergencelabs.server.datastore.domain.DomainPersistenceManagerActor
import com.convergencelabs.server.datastore.domain.DomainPersistenceManager
import com.convergencelabs.server.datastore.domain.DomainPersistenceProvider
import com.convergencelabs.server.datastore.domain.ModelPermissions
import com.convergencelabs.server.domain.DomainFqn
import com.convergencelabs.server.domain.ModelSnapshotConfig
import com.convergencelabs.server.domain.model.data.ObjectValue

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Status
import akka.actor.Terminated

case class QueryModelsRequest(sk: SessionKey, query: String)
case class QueryOrderBy(field: String, ascending: Boolean)

case class QueryModelsResponse(result: List[Model])

class ModelManagerActor(
  private[this] val domainFqn: DomainFqn,
  private[this] val protocolConfig: ProtocolConfiguration,
  private[this] val persistenceManager: DomainPersistenceManager)
    extends Actor with ActorLogging {

  private[this] var openRealtimeModels = Map[ModelFqn, ActorRef]()
  private[this] var nextModelResourceId: Long = 0

  var persistenceProvider: DomainPersistenceProvider = _

  def receive: Receive = {
    case message: OpenRealtimeModelRequest => onOpenRealtimeModel(message)
    case message: CreateModelRequest => onCreateModelRequest(message)
    case message: DeleteModelRequest => onDeleteModelRequest(message)
    case message: QueryModelsRequest => onQueryModelsRequest(message)
    case message: ModelShutdownRequest => onModelShutdownRequest(message)
    case message: GetModelPermissionsRequest => onGetModelPermissions(message)
    case message: SetModelPermissionsRequest => onSetModelPermissions(message)
    case Terminated(actor) => onActorDeath(actor)
    case message: Any => unhandled(message)
  }

  private[this] def onOpenRealtimeModel(openRequest: OpenRealtimeModelRequest): Unit = {
    this.openRealtimeModels.get(openRequest.modelFqn) match {
      case Some(modelActor) =>
        // Model already open
        modelActor forward openRequest
      case None =>
        // Model not already open, load it
        val me = this.persistenceProvider.modelStore.modelExists(openRequest.modelFqn)
        me flatMap { exists =>
          (if (exists) {
            canOpen(openRequest.modelFqn, openRequest.sk)
          } else {
            canCreate(openRequest.modelFqn.collectionId, openRequest.sk)
          }) map { allowed =>
            (allowed, exists)
          }
        } flatMap {
          case (canOpenModel, modelExists) =>
            if (canOpenModel) {
              val resourceId = "" + nextModelResourceId
              nextModelResourceId += 1
              val collectionId = openRequest.modelFqn.collectionId

              (for {
                collectionExists <- persistenceProvider.collectionStore.ensureCollectionExists(collectionId)
                snapshotConfig <- getSnapshotConfigForModel(collectionId)
                permissions <- getModelPermissions(openRequest.modelFqn, modelExists)
              } yield {
                val props = RealtimeModelActor.props(
                  self,
                  domainFqn,
                  openRequest.modelFqn,
                  resourceId,
                  persistenceProvider.modelStore,
                  persistenceProvider.modelOperationProcessor,
                  persistenceProvider.modelSnapshotStore,
                  5000, // FIXME hard-coded time.  Should this be part of the protocol?
                  snapshotConfig,
                  permissions)

                val modelActor = context.actorOf(props, resourceId)
                this.openRealtimeModels += (openRequest.modelFqn -> modelActor)
                this.context.watch(modelActor)
                modelActor forward openRequest
                ()
              })
            } else {
              val message = if (modelExists) {
                "Insufficient privlidges to open the specified model."
              } else {
                "Insufficient privlidges to create the specified model."
              }
              sender ! UnauthorizedException(message)
              Success(())
            }
        } recover {
          case cause: Exception =>
            log.error(cause, s"Error opening model: ${openRequest.modelFqn}")
            sender ! UnknownErrorResponse("Could not open model due to an unexpected server error.")
        }
    }
  }

  private[this] def getCollectionUserPermissions(collectionId: String, username: String): Try[Option[CollectionPermissions]] = {
    val permissionsStore = this.persistenceProvider.modelPermissionsStore
    permissionsStore.getCollectionUserPermissions(collectionId, username).flatMap { userPermissions =>
      userPermissions match {
        case Some(p) =>
          Success(Some(p))
        case None =>
          permissionsStore.getCollectionWorldPermissions(collectionId)
      }
    }
  }

  private[this] def getModelUserPermissions(fqn: ModelFqn, username: String): Try[ModelPermissions] = {
    val permissionsStore = this.persistenceProvider.modelPermissionsStore
    permissionsStore.modelOverridesCollectionPermissions(fqn).flatMap { overrides =>
      if (overrides) {
        permissionsStore.getModelUserPermissions(fqn, username).flatMap { userPerms =>
          userPerms match {
            case Some(p) =>
              Success(p)
            case None =>
              permissionsStore.getModelWorldPermissions(fqn)
          }
        }
      } else {
        permissionsStore.getCollectionWorldPermissions(fqn.collectionId).flatMap { collectionPerms =>
          collectionPerms match {
            case Some(c) =>
              val CollectionPermissions(create, read, write, remove, manage) = c
              Success(ModelPermissions(read, write, remove, manage))
            case None =>
              // TODO we should actually clean this up. When we get here, we know that the collection does
              // not exist, which means the model can't exist.
              Failure(new IllegalArgumentException("Can not get permissions for a model that does not exists"))
          }
        }
      }
    }
  }

  private[this] def getModelPermissions(fqn: ModelFqn, modelExists: Boolean): Try[RealTimeModelPermissions] = {
    val permissionsStore = this.persistenceProvider.modelPermissionsStore
    if (modelExists) {
      for {
        overrideCollection <- permissionsStore.modelOverridesCollectionPermissions(fqn)
        collectionWorld <- permissionsStore.getCollectionWorldPermissions(fqn.collectionId)
        collectionUsers <- permissionsStore.getAllCollectionUserPermissions(fqn.collectionId)
        modelWorld <- permissionsStore.getModelWorldPermissions(fqn)
        modelUsers <- permissionsStore.getAllModelUserPermissions(fqn)
      } yield (RealTimeModelPermissions(overrideCollection, collectionWorld.get, collectionUsers, modelWorld, modelUsers))
    } else {
      for {
        collectionWorld <- permissionsStore.getCollectionWorldPermissions(fqn.collectionId)
        collectionUsers <- permissionsStore.getAllCollectionUserPermissions(fqn.collectionId)
      } yield (RealTimeModelPermissions(
        false,
        collectionWorld.get,
        collectionUsers,
        ModelPermissions(false, false, false, false),
        Map()))
    }
  }

  private[this] def getSnapshotConfigForModel(collectionId: String): Try[ModelSnapshotConfig] = {
    persistenceProvider.collectionStore.getOrCreateCollection(collectionId).flatMap { c =>
      if (c.overrideSnapshotConfig) {
        Success(c.snapshotConfig)
      } else {
        persistenceProvider.configStore.getModelSnapshotConfig()
      }
    }
  }

  private[this] def onCreateModelRequest(createRequest: CreateModelRequest): Unit = {
    val CreateModelRequest(sk, collectionId, modelId, data, overridePermissions, worldPermissions) = createRequest
    // FIXME perhaps these should be some expected error type, like InvalidArgument
    if (collectionId.length == 0) {
      sender ! UnknownErrorResponse("The collecitonId can not be empty when creating a model")
    } else {
      canCreate(collectionId, sk) flatMap { canCreateModel =>
        if (canCreateModel) {
          ModelCreator.createModel(
            persistenceProvider,
            Some(sk.uid),
            collectionId,
            modelId,
            data,
            overridePermissions,
            worldPermissions
          ) map { model =>
            sender ! ModelCreated(model.metaData.fqn)
            ()
          }
        } else {
          // FIXME I don't think this is doing anything?
          sender ! UnauthorizedException("Insufficient privlidges to create models for this collection")
          Success(())
        }
      } recover {
        case e: DuplicateValueExcpetion =>
          sender ! ModelAlreadyExists
        case e: InvalidValueExcpetion =>
          sender ! UnknownErrorResponse("Could not create model beause it contained an invalid value")
        case e: Exception =>
          log.error(e, s"Could not create model: ${collectionId} / ${modelId}")
          sender ! UnknownErrorResponse("Could not create model: " + e.getMessage)
      }
    }
  }

  private[this] def onDeleteModelRequest(deleteRequest: DeleteModelRequest): Unit = {
    val DeleteModelRequest(sk, modelFqn) = deleteRequest

    val canDelete = if (sk.admin) {
      Success(true)
    } else {
      getModelUserPermissions(modelFqn, sk.uid) map (p => p.remove)
    }

    canDelete.flatMap { canDelete =>
      if (canDelete) {
        if (openRealtimeModels.contains(modelFqn)) {
          val closed = openRealtimeModels(modelFqn)
          closed ! ModelDeleted
          openRealtimeModels -= modelFqn
        }

        persistenceProvider.modelStore.deleteModel(modelFqn) map { _ =>
          sender ! ModelDeleted
          ()
        }
      } else {
        sender ! UnauthorizedException("Insufficient privlidges to delete model")
        Success(())
      }
    } recover {
      case cause: Exception =>
        sender ! Status.Failure(cause)
    }
  }

  private[this] def onQueryModelsRequest(request: QueryModelsRequest): Unit = {
    val QueryModelsRequest(sk, query) = request
    val username = if (request.sk.admin) {
      None
    } else {
      Some(request.sk.uid)
    }
    persistenceProvider.modelStore.queryModels(query, username) match {
      case Success(result) => sender ! QueryModelsResponse(result)
      case Failure(cause) => sender ! Status.Failure(cause)
    }
  }

  private[this] def onGetModelPermissions(request: GetModelPermissionsRequest): Unit = {
    val GetModelPermissionsRequest(collectionId, modelId) = request
    // FIXME need to implement getting this from the database
    sender ! GetModelPermissionsResponse(ModelPermissions(true, true, true, true), Map())
  }

  private[this] def onSetModelPermissions(request: SetModelPermissionsRequest): Unit = {
    val SetModelPermissionsRequest(sk, collectionId, modelId, overrideCollection, world, setAllUsers, users) = request
    val modelFqn = ModelFqn(collectionId, modelId)
    this.openRealtimeModels.get(modelFqn).foreach { _ forward request }
    overrideCollection.foreach { persistenceProvider.modelPermissionsStore.setOverrideCollectionPermissions(modelFqn, _) }
    world.foreach { persistenceProvider.modelPermissionsStore.setModelWorldPermissions(modelFqn, _) }

    if (setAllUsers) {
      persistenceProvider.modelPermissionsStore.deleteAllModelUserPermissions(modelFqn)
    }

    persistenceProvider.modelPermissionsStore.updateAllModelUserPermissions(modelFqn, users)
  }

  private[this] def canCreate(collectionId: String, sk: SessionKey): Try[Boolean] = {
    if (sk.admin) {
      Success(true)
    } else {
      // Eventually we need some sort of domain wide configuration to allow / disallow auto creation of
      // collections.
      getCollectionUserPermissions(collectionId, sk.uid) map { c =>
        c map (x => x.create) getOrElse (true)
      }
    }
  }

  private[this] def canOpen(fqn: ModelFqn, sk: SessionKey): Try[Boolean] = {
    if (sk.admin) {
      Success(true)
    } else {
      getModelUserPermissions(fqn, sk.uid) map (p => p.read)
    }
  }

  private[this] def onModelShutdownRequest(shutdownRequest: ModelShutdownRequest): Unit = {
    val fqn = shutdownRequest.modelFqn
    openRealtimeModels.get(fqn) map (_ ! ModelShutdown)
    openRealtimeModels -= (fqn)
  }

  private[this] def onActorDeath(actor: ActorRef): Unit = {
    // TODO might be more efficient ay to do this.
    openRealtimeModels = openRealtimeModels filter {
      case (fqn, modelActorRef) =>
        actor != modelActorRef
    }
  }

  override def postStop(): Unit = {
    log.debug("ModelManagerActor({}) received shutdown command.  Shutting down all Realtime Models.", this.domainFqn)
    openRealtimeModels = Map()
    persistenceManager.releasePersistenceProvider(self, context, domainFqn)
  }

  override def preStart(): Unit = {
    persistenceManager.acquirePersistenceProvider(self, context, domainFqn) match {
      case Success(provider) =>
        persistenceProvider = provider
      case Failure(cause) =>
        throw new IllegalStateException("Could not obtain a persistence provider", cause)
    }
  }
}

object ModelManagerActor {

  val RelativePath = "modelManager"

  def props(domainFqn: DomainFqn,
    protocolConfig: ProtocolConfiguration,
    persistenceManager: DomainPersistenceManager): Props = Props(
    new ModelManagerActor(
      domainFqn,
      protocolConfig,
      persistenceManager))
}
