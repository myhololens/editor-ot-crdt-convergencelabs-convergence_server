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

package com.convergencelabs.convergence.server.datastore.domain

import com.convergencelabs.convergence.server.datastore.EntityNotFoundException
import com.convergencelabs.convergence.server.db.DatabaseProvider
import com.convergencelabs.convergence.server.db.schema.DeltaCategory
import com.convergencelabs.convergence.server.domain.{DomainUser, DomainUserId}
import com.convergencelabs.convergence.server.domain.model.data.ObjectValue
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.{Matchers, WordSpecLike}

class ModelPermissionsStoreSpec
  extends PersistenceStoreSpec[DomainPersistenceProvider](DeltaCategory.Domain)
    with WordSpecLike
    with Matchers {

  val modelPermissions = ModelPermissions(read = true, write = true, remove = true, manage = true)

  val collection1 = "collection1"
  val model1 = "model1"
  val model2 = "model2"
  val user1 = DomainUserId.normal("user1")
  val user2 = DomainUserId.normal("user2")
  val nonExistentCollectionId = "not_real"
  val nonRealId = "not_real"

  def createStore(dbProvider: DatabaseProvider): DomainPersistenceProvider = new DomainPersistenceProviderImpl(dbProvider)

  "A ModelPermissionsStore" when {
    "retrieving the model world permissions" must {
      "be equal to those just set" in withTestData { provider =>
        val permissions = ModelPermissions(read = true, write = false, remove = true, manage = false)
        provider.modelPermissionsStore.setModelWorldPermissions(model1, permissions).get
        val retrievedPermissions = provider.modelPermissionsStore.getModelWorldPermissions(model1).get
        retrievedPermissions shouldEqual permissions
      }

      "fail if model does not exist" in withTestData { provider =>
        an[EntityNotFoundException] should be thrownBy provider.modelPermissionsStore.getModelWorldPermissions(nonRealId).get
      }
    }

    "retrieving the model user permissions" must {
      "be equal to those just set" in withTestData { provider =>
        val permissions = ModelPermissions(read = true, write = false, remove = true, manage = false)
        provider.modelPermissionsStore.updateModelUserPermissions(model1, user1, permissions).get
        val retrievedPermissions = provider.modelPermissionsStore.getModelUserPermissions(model1, user1).get
        retrievedPermissions shouldEqual Some(permissions)
      }

      "be none if no permissions are set" in withTestData { provider =>
        val retrievedPermissions = provider.modelPermissionsStore.getModelUserPermissions(model1, user1).get
        retrievedPermissions shouldEqual None
      }
    }

    "retrieving all model user permissions" must {
      "contain all those just set" in withTestData { provider =>
        val permissions = ModelPermissions(read = true, write = false, remove = true, manage = false)
        provider.modelPermissionsStore.updateModelUserPermissions(model1, user1, permissions).get
        provider.modelPermissionsStore.updateModelUserPermissions(model1, user2, permissions).get
        val retrievedPermissions = provider.modelPermissionsStore.getAllModelUserPermissions(model1).get
        retrievedPermissions shouldEqual Map(user1 -> permissions, user2 -> permissions)
      }

      "fail if model does not exist" in withTestData { provider =>
        an[EntityNotFoundException] should be thrownBy provider.modelPermissionsStore.getAllModelUserPermissions(nonRealId).get
      }

      "contain all those just set via update all" in withTestData { provider =>
        val permissions = ModelPermissions(read = true, write = false, remove = true, manage = false)
        provider.modelPermissionsStore.updateAllModelUserPermissions(model1, Map(user1 -> Some(permissions))).get
        provider.modelPermissionsStore.updateAllModelUserPermissions(model1, Map(user2 -> Some(permissions))).get
        val retrievedPermissions = provider.modelPermissionsStore.getAllModelUserPermissions(model1).get
        retrievedPermissions shouldEqual Map(user1 -> permissions, user2 -> permissions)
      }
    }

    "deleting a model user permissions" must {
      "must no longer be set on the model" in withTestData { provider =>
        val permissions = ModelPermissions(read = true, write = false, remove = true, manage = false)
        provider.modelPermissionsStore.updateModelUserPermissions(model1, user1, permissions).get
        provider.modelPermissionsStore.updateModelUserPermissions(model1, user2, permissions).get
        provider.modelPermissionsStore.removeModelUserPermissions(model1, user1)
        val retrievedPermissions = provider.modelPermissionsStore.getAllModelUserPermissions(model1).get
        retrievedPermissions shouldEqual Map(user2 -> permissions)
      }
    }

    "retrieving the collection world permissions" must {
      "be equal to those just set" in withTestData { provider =>
        val permissions = CollectionPermissions(create = false, read = true, write = false, remove = true, manage = false)
        provider.modelPermissionsStore.setCollectionWorldPermissions(collection1, permissions).get
        val retrievedPermissions = provider.modelPermissionsStore.getCollectionWorldPermissions(collection1).get
        retrievedPermissions shouldEqual permissions
      }

      "fail if collection does not exist" in withTestData { provider =>
        provider.modelPermissionsStore.getCollectionWorldPermissions(nonExistentCollectionId).failure
      }
    }

    "retrieving the collection user permissions" must {
      "be equal to those just set" in withTestData { provider =>
        val permissions = CollectionPermissions(create = false, read = true, write = false, remove = true, manage = false)
        provider.modelPermissionsStore.updateCollectionUserPermissions(collection1, user1, permissions).get
        val retrievedPermissions = provider.modelPermissionsStore.getCollectionUserPermissions(collection1, user1).get
        retrievedPermissions shouldEqual Some(permissions)
      }

      "be none if no permissions are set" in withTestData { provider =>
        val retrievedPermissions = provider.modelPermissionsStore.getCollectionUserPermissions(collection1, user1).get
        retrievedPermissions shouldEqual None
      }
    }

    "retrieving all collection user permissions" must {
      "contain all those just set" in withTestData { provider =>
        val permissions = CollectionPermissions(create = false, read = true, write = false, remove = true, manage = false)
        provider.modelPermissionsStore.updateCollectionUserPermissions(collection1, user1, permissions).get
        provider.modelPermissionsStore.updateCollectionUserPermissions(collection1, user2, permissions).get
        val retrievedPermissions = provider.modelPermissionsStore.getAllCollectionUserPermissions(collection1).get
        retrievedPermissions shouldEqual Map(user1 -> permissions, user2 -> permissions)
      }

      "fail if collection does not exist" in withTestData { provider =>
        an[EntityNotFoundException] should be thrownBy provider.modelPermissionsStore.getAllCollectionUserPermissions(nonExistentCollectionId).get
      }
    }

    "deleting a collection user permissions" must {
      "must no longer be set on the collection" in withTestData { provider =>
        val permissions = CollectionPermissions(create = false, read = true, write = false, remove = true, manage = false)
        provider.modelPermissionsStore.updateCollectionUserPermissions(collection1, user1, permissions).get
        provider.modelPermissionsStore.updateCollectionUserPermissions(collection1, user2, permissions).get
        provider.modelPermissionsStore.removeCollectionUserPermissions(collection1, user1)
        val retrievedPermissions = provider.modelPermissionsStore.getAllCollectionUserPermissions(collection1).get
        retrievedPermissions shouldEqual Map(user2 -> permissions)
      }
    }
  }

  def withTestData(testCode: DomainPersistenceProvider => Any): Unit = {
    this.withPersistenceStore { provider =>
      provider.collectionStore.ensureCollectionExists(collection1).get
      provider.modelStore.createModel(model1, collection1, ObjectValue("vid", Map()), overridePermissions = true, modelPermissions).get
      provider.modelStore.createModel(model2, collection1, ObjectValue("vid", Map()), overridePermissions = true, modelPermissions).get
      provider.userStore.createDomainUser(DomainUser(user1.userType, user1.username, None, None, None, None, None)).get
      provider.userStore.createDomainUser(DomainUser(user2.userType, user2.username, None, None, None, None, None)).get
      testCode(provider)
    }
  }
}
