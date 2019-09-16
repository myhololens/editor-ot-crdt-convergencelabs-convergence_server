package com.convergencelabs.server.domain

import com.convergencelabs.server.datastore.domain.ModelPermissions
import com.convergencelabs.server.domain.model.data.ObjectValue

package model {

  case class ModelFqn(domainFqn: DomainId, modelId: String)
  
  case class ClientAutoCreateModelConfigResponse(collectionId: String, modelData: Option[ObjectValue], overridePermissions: Option[Boolean],
    worldPermissions: Option[ModelPermissions], userPermissions: Map[DomainUserId, ModelPermissions], ephemeral: Option[Boolean])

  case class ModelNotFoundException(modelId: String) extends Exception(s"A model with id '$modelId' does not exist.")
  case class ModelAlreadyExistsException(modelId: String) extends Exception(s"A model with id '$modelId' already exists.")

  case class CreateCollectionRequest(collection: Collection)
  case class UpdateCollectionRequest(collection: Collection)
  case class DeleteCollectionRequest(collectionId: String)
  case class GetCollectionRequest(collectionId: String)
  case object GetCollectionsRequest

  case class ModelAlreadyOpenException() extends RuntimeException()
  case class ModelAlreadyOpeningException() extends RuntimeException()
  case class ModelNotOpenException() extends RuntimeException()
  case class ModelDeletedWhileOpeningException() extends RuntimeException()
  case class ClientDataRequestFailure(message: String) extends RuntimeException(message)
  
  case class ModelShutdownRequest(modelId: String, ephemeral: Boolean)

  case class ModelReconnectComplete(modelId: String)
  case class ModelServerStatePathRejoined(modelId: String)
  case class ModelReconnectFailed(modelId: String, message: String)

  object ReferenceType extends Enumeration {
    val Index, Range, Property, Element = Value
  }

  case class ReferenceValue(id: Option[String], key: String, referenceType: ReferenceType.Value, values: List[Any], contextVersion: Long)

  case class ReferenceState(
    session: DomainUserSessionId,
    valueId: Option[String],
    key: String,
    referenceType: ReferenceType.Value,
    values: List[Any])
}