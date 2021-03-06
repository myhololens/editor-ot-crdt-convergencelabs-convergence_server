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

package com.convergencelabs.convergence.server.datastore.domain.mapper

import org.scalatest.Finders
import org.scalatest.Matchers
import org.scalatest.WordSpec

import com.convergencelabs.convergence.server.domain.model.data.StringValue
import com.convergencelabs.convergence.server.domain.model.ot.AppliedObjectSetOperation
import com.orientechnologies.orient.core.record.impl.ODocument

import ObjectSetOperationMapper.ODocumentToObjectSetOperation
import ObjectSetOperationMapper.ObjectSetOperationToODocument

class ObjectSetOperationMapperSpec
    extends WordSpec
    with Matchers {

  "An ObjectSetOperationMapper" when {
    "when converting ObjectSetOperation operations" must {
      "correctly map and unmap a ObjectSetOperation" in {
        val op = AppliedObjectSetOperation("vid", true, Map("foo" -> StringValue("vid2", "test")), Some(Map("old" -> StringValue("oldId", "oldValue"))))
        val opDoc = op.asODocument
        val reverted = opDoc.asObjectSetOperation
        op shouldBe reverted
      }

      "not allow an invalid document class name" in {
        val invalid = new ODocument("SomeClass")
        intercept[IllegalArgumentException] {
          invalid.asObjectSetOperation
        }
      }
    }
  }
}
