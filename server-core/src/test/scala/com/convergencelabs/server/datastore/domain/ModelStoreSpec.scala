package com.convergencelabs.server.datastore.domain

import java.time.Instant
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.scalatest.Finders
import org.scalatest.Matchers
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.TryValues._
import org.scalatest.WordSpecLike
import com.convergencelabs.server.domain.model.Model
import com.convergencelabs.server.domain.model.ModelFqn
import com.convergencelabs.server.domain.model.ModelMetaData
import com.orientechnologies.orient.core.db.OPartitionedDatabasePool
import com.orientechnologies.orient.core.storage.ORecordDuplicatedException
import java.text.SimpleDateFormat

class ModelStoreSpec
    extends PersistenceStoreSpec[ModelStore]("/dbfiles/domain.json.gz")
    with WordSpecLike
    with Matchers {

  val df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

  def createStore(dbPool: OPartitionedDatabasePool): ModelStore = new ModelStore(dbPool)

  val person1 = ModelFqn("people", "person1")
  val person1MetaData = ModelMetaData(
    person1,
    20,
    Instant.ofEpochMilli(df.parse("2015-10-20 01:00:00").getTime),
    Instant.ofEpochMilli(df.parse("2015-10-20 12:00:00").getTime))

  val person2 = ModelFqn("people", "person2")
  val person2MetaData = ModelMetaData(
    person2,
    0,
    Instant.ofEpochMilli(df.parse("2015-10-20 02:00:00").getTime),
    Instant.ofEpochMilli(df.parse("2015-10-20 02:00:00").getTime))

  val person3 = ModelFqn("people", "person3")
  val person3MetaData = ModelMetaData(
    person3,
    0,
    Instant.ofEpochMilli(df.parse("2015-10-20 03:00:00").getTime),
    Instant.ofEpochMilli(df.parse("2015-10-20 03:00:00").getTime))

  val company1 = ModelFqn("company", "company1")
  val company1MetaData = ModelMetaData(
    company1,
    0,
    Instant.ofEpochMilli(df.parse("2015-10-20 04:00:00").getTime),
    Instant.ofEpochMilli(df.parse("2015-10-20 04:00:00").getTime))

  val nonExsitingFqn = ModelFqn("notRealCollection", "notRealModel")

  "An ModelStore" when {

    "asked whether a model exists" must {

      "return false if it doesn't exist" in withPersistenceStore { store =>
        store.modelExists(nonExsitingFqn).success.value shouldBe false
      }

      "return true if it does exist" in withPersistenceStore { store =>
        store.modelExists(person1).success.value shouldBe true
      }
    }

    "creating a model" must {
      "create a model that is not a duplicate model fqn" in withPersistenceStore { store =>
        val modelFqn = ModelFqn("people", "person4")
        val time = Instant.now()
        val model = Model(
          ModelMetaData(
            modelFqn,
            0L,
            time,
            time),
          JObject("foo" -> JString("bar")))

        store.createModel(model).success
        store.getModel(modelFqn).success.value.value shouldBe model
      }

      "not create a model that is not a duplicate model fqn" in withPersistenceStore { store =>
        val time = Instant.now()
        val model = Model(
          ModelMetaData(
            person1,
            0L,
            time,
            time),
          JObject("foo" -> JString("bar")))

        store.createModel(model).failure.exception shouldBe a[ORecordDuplicatedException]
      }
    }

    "getting a model" must {
      "return None if it doesn't exist" in withPersistenceStore { store =>
        store.getModel(nonExsitingFqn).success.value shouldBe None
      }

      "return Some if it does exist" in withPersistenceStore { store =>
        store.getModel(person1).success.value shouldBe defined
      }
    }

    "getting model meta data for a specific model" must {
      "return the correct meta data if it exists" in withPersistenceStore { store =>
        store.getModelMetaData(person1).success.value.value shouldBe person1MetaData
      }

      "return None if it does not exist" in withPersistenceStore { store =>
        store.getModelMetaData(nonExsitingFqn).success.value shouldBe None
      }
    }

    "getting all model meta data for a specific collection" must {
      "return all meta data when no limit or offset are provided" in withPersistenceStore { store =>
        val list = store.getAllModelMetaDataInCollection("people", None, None).success.value
        list shouldBe List(
          person1MetaData,
          person2MetaData,
          person3MetaData)
      }

      "return only the limited number of meta data when limit provided" in withPersistenceStore { store =>
        val list = store.getAllModelMetaDataInCollection("people", None, Some(1)).success.value
        list shouldBe List(person1MetaData)
      }

      "return only the limited number of meta data when offset provided" in withPersistenceStore { store =>
        val list = store.getAllModelMetaDataInCollection("people", Some(1), None).success.value
        list.length shouldBe 2
        list(0) shouldBe person2MetaData
        list(1) shouldBe person3MetaData
      }

      "return only the limited number of meta data when limit and offset provided" in withPersistenceStore { store =>
        val list = store.getAllModelMetaDataInCollection("people", Some(1), Some(1)).success.value
        list shouldBe List(person2MetaData)
      }

    }

    "getting all model meta data" must {
      "return all meta data when no limit or offset are provided" in withPersistenceStore { store =>
        val list = store.getAllModelMetaData(None, None).success.value
        list shouldBe List(
          company1MetaData,
          person1MetaData,
          person2MetaData,
          person3MetaData)
      }

      "return correct meta data when a limit is provided" in withPersistenceStore { store =>
        val list = store.getAllModelMetaData(None, Some(2)).success.value
        list shouldBe List(
          company1MetaData,
          person1MetaData)
      }

      "return correct meta data when an offset is provided" in withPersistenceStore { store =>
        val list = store.getAllModelMetaData(Some(2), None).success.value
        list shouldBe List(
          person2MetaData,
          person3MetaData)
      }

      "return correct meta data when an offset and limit are provided" in withPersistenceStore { store =>
        val list = store.getAllModelMetaData(Some(1), Some(2)).success.value
        list shouldBe List(
          person1MetaData,
          person2MetaData)
      }
    }

    "deleting a specific model" must {
      "delete the specified model and no others" in withPersistenceStore { store =>
        store.getModel(person1).success.value shouldBe defined
        store.getModel(person2).success.value shouldBe defined
        store.getModel(company1).success.value shouldBe defined

        store.deleteModel(person1).success

        store.getModel(person1).success.value shouldBe None
        store.getModel(person2).success.value shouldBe defined
        store.getModel(company1).success.value shouldBe defined
      }

      "return a failure for deleting a non-existent model" in withPersistenceStore { store =>
        store.getModel(nonExsitingFqn).success.value shouldBe None
        store.deleteModel(nonExsitingFqn).failure
      }
    }
    
    "deleting all models in collection" must {
      "delete the models in the specified and no others" in withPersistenceStore { store =>
        store.getModel(person1).success.value shouldBe defined
        store.getModel(person2).success.value shouldBe defined
        store.getModel(person3).success.value shouldBe defined
        store.getModel(company1).success.value shouldBe defined

        store.deleteAllModelsInCollection(person1.collectionId).success

        store.getModel(person1).success.value shouldBe None
        store.getModel(person2).success.value shouldBe None
        store.getModel(person3).success.value shouldBe None
        store.getModel(company1).success.value shouldBe defined
      }
    }
  }
}
