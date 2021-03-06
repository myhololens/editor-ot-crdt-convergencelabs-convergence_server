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

package com.convergencelabs.convergence.server.datastore.mapper

import org.scalatest.WordSpec
import org.scalatest.Matchers
import com.orientechnologies.orient.core.record.impl.ODocument

// scalastyle:off null
class ODocumentMapperSpec
    extends WordSpec
    with Matchers {

  "An ODocumentMapper" when {

    "translating an option to a value or null" must {

      "translate Some(value) to a value" in {
        val value = new Object()
        val mapper = new ODocumentMapper() {}
        mapper.valueOrNull(Some(value)) shouldBe value
      }

      "translate None to null" in {
        val value = new Object()
        val mapper = new ODocumentMapper() {}
        val result: Any = mapper.valueOrNull(None)
        assert(result == null)
      }
    }

    "translating a nullable value to an option" must {

      "translate a non-null value to Some(value)" in {
        val value = new Object()
        val mapper = new ODocumentMapper() {}
        mapper.toOption(value) shouldBe Some(value)
      }

      "translate null to None" in {
        val value = new Object()
        val mapper = new ODocumentMapper() {}
        mapper.toOption(null) shouldBe None
      }
    }

    "ating a document class name" must {

      "not throw an excpetion for a matching class name" in {
        val mapper = new ODocumentMapper() {}
        val className = "class"
        mapper.validateDocumentClass(new ODocument(className), className)
      }

      "throw an excpetion for a non-matching class name" in {
        val mapper = new ODocumentMapper() {}
        intercept[IllegalArgumentException] {
          mapper.validateDocumentClass(new ODocument("Correct"), "Incorrect")
        }
      }
    }
  }
}
