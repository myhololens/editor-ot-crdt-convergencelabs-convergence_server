package com.convergencelabs.server.datastore.domain.mapper

import org.scalatest.Finders
import org.scalatest.Matchers
import org.scalatest.WordSpec
import com.convergencelabs.server.domain.model.ot.ObjectSetPropertyOperation
import com.orientechnologies.orient.core.record.impl.ODocument
import ObjectSetPropertyOperationMapper.ObjectSetPropertyOperationToODocument
import ObjectSetPropertyOperationMapper.ODocumentToObjectSetPropertyOperation
import com.convergencelabs.server.domain.model.data.StringValue

class ObjectSetPropertyOperationMapperSpec
    extends WordSpec
    with Matchers {

  "An ObjectSetPropertyOperationMapper" when {
    "when converting ObjectSetPropertyOperation operations" must {
      "correctly map and unmap a ObjectSetPropertyOperation" in {
        val op = ObjectSetPropertyOperation("vid", true, "foo", StringValue("vid1", "bar"))
        val opDoc = op.asODocument
        val reverted = opDoc.asObjectSetPropertyOperation
        op shouldBe reverted
      }

      "not allow an invalid document class name" in {
        val invalid = new ODocument("SomeClass")
        intercept[IllegalArgumentException] {
          invalid.asObjectSetPropertyOperation
        }
      }
    }
  }
}