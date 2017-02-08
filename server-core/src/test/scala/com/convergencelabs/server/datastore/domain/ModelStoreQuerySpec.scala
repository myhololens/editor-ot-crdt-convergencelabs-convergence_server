package com.convergencelabs.server.datastore.domain

import java.text.SimpleDateFormat
import java.time.Instant

import org.scalatest.Finders
import org.scalatest.Matchers
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.WordSpecLike

import com.convergencelabs.server.datastore.DatabaseProvider
import com.convergencelabs.server.datastore.DuplicateValueExcpetion
import com.convergencelabs.server.datastore.EntityNotFoundException
import com.convergencelabs.server.db.schema.DeltaCategory
import com.convergencelabs.server.domain.model.Model
import com.convergencelabs.server.domain.model.ModelFqn
import com.convergencelabs.server.domain.model.ModelMetaData
import com.convergencelabs.server.domain.model.data.ObjectValue
import com.convergencelabs.server.domain.model.data.StringValue
import com.convergencelabs.server.domain.model.data.ArrayValue
import com.convergencelabs.server.db.data.DomainScriptSerializer
import com.convergencelabs.server.db.data.DomainImporter
import org.json4s._
import org.json4s.jackson.JsonMethods._
import scala.language.postfixOps
import com.convergencelabs.server.domain.model.data.BooleanValue
import com.convergencelabs.server.domain.model.data.DoubleValue

case class ModelStoreQuerySpecStores(collection: CollectionStore, model: ModelStore)

// scalastyle:off magic.number
class ModelStoreQuerySpec extends PersistenceStoreSpec[ModelStoreQuerySpecStores](DeltaCategory.Domain) with WordSpecLike with Matchers {

  implicit val formats = DefaultFormats
  var vid = 0

  def createStore(dbProvider: DatabaseProvider): ModelStoreQuerySpecStores = {
    val modelStore = new ModelStore(dbProvider, new ModelOperationStore(dbProvider), new ModelSnapshotStore(dbProvider))
    val collectionStore = new CollectionStore(dbProvider, modelStore)
    ModelStoreQuerySpecStores(collectionStore, modelStore)
  }

  "An ModelStore" when {

    "querying model data" must {
      "return only models in a single collection" in withPersistenceStore { stores =>
        createModels(stores)
        val list = stores.model.queryModels("SELECT FROM collection1").get
        println(list)
      }
    }
  }

  private[this] def jsonStringToModel(jsonString: String): Model = {
    val json = parse(jsonString)

    val collectionId = (json \ "collection").extract[String]
    val modelId = (json \ "id").extract[String]
    val version = (json \ "version").extract[Long]
    val created = (json \ "created").extract[Long]
    val modified = (json \ "modified").extract[Long]

    val metaData = ModelMetaData(ModelFqn(collectionId, modelId), version, Instant.ofEpochMilli(created), Instant.ofEpochMilli(modified))

    Model(metaData, jObjectToObjectValue((json \ "data").asInstanceOf[JObject]))
  }

  private[this] def jObjectToObjectValue(jObject: JObject): ObjectValue = {
    val fieldMap = jObject.obj.map {
      case JField(field, value) =>
        field -> (value match {
          case value: JObject => jObjectToObjectValue(value)
          case value: JArray  => jArrayToArrayValue(value)
          case JString(value) => StringValue(nextId(), value)
          case JBool(value)   => BooleanValue(nextId(), value)
          case JInt(value)    => DoubleValue(nextId(), value.toDouble)
          case _              => ???
        })
    }
    ObjectValue(nextId(), fieldMap toMap)
  }

  private[this] def jArrayToArrayValue(jArray: JArray): ArrayValue = {
    val fields = jArray.arr.map {
      value =>
        value match {
          case value: JObject => jObjectToObjectValue(value)
          case value: JArray  => jArrayToArrayValue(value)
          case JString(value) => StringValue(nextId(), value)
          case JBool(value)   => BooleanValue(nextId(), value)
          case JInt(value)    => DoubleValue(nextId(), value.toDouble)
          case _              => ???
        }
    }
    ArrayValue(nextId(), fields)
  }

  private[this] def nextId(): String = {
    vid += 1
    s"${vid}"
  }

  private[this] def createModels(stores: ModelStoreQuerySpecStores): Unit = {
    stores.collection.ensureCollectionExists("collection1")
    stores.model.createModel(jsonStringToModel("""{
           "collection": "collection1",
           "id": "model1",
           "version": 10,
           "created": 1486577481766,
           "modified": 1486577481766,
           "data": {
             "sField": "myString",
             "bField": true,
             "iField": 25,
             "oField": {
               "oField1": "model1String",
               "oField2": 1
             },
             "arrayField": ["A", "B", "C"]
           }
          }""")).get

    stores.model.createModel(jsonStringToModel("""{
           "collection": "collection1",
           "id": "model2",
           "version": 20,
           "created": 1486577481766,
           "modified": 1486577481766,
           "data": {
             "sField": "myString2",
             "bField": false,
             "iField": 28,
             "oField": {
               "oField1": "model2String",
               "oField2": 2
             },
             "arrayField": ["D", "E", "F"]
           }
          }""")).get
  }
}