package com.convergencelabs.server.domain.model.ot.xform.reference

import org.scalatest.WordSpec
import org.scalatest.Matchers
import com.convergencelabs.server.domain.model.ot.xform.IndexTransformer

class IndexTransformerSpec
    extends WordSpec
    with Matchers {

  "An IndexTransformer" when {
    "tranforming indices against an insert" must {
      "Increment indices that are greaterthan by the length of the insert" in {
        IndexTransformer.handleInsert(List(6), 5, 2) shouldBe List(8)
      }

      "Increment indices that are equal to by the length of the insert" in {
        IndexTransformer.handleInsert(List(6), 6, 2) shouldBe List(8)
      }

      "Does not increment indices that are less than the insert index" in {
        IndexTransformer.handleInsert(List(4), 5, 2) shouldBe List(4)
      }

      "Properly transforms multiple items in a list against an insert" in {
        IndexTransformer.handleInsert(List(4, 5, 6), 5, 2) shouldBe List(4, 7, 8)
      }
    }

    "tranforming indices against a remove" must {
      "Decrement indices that are greater than the remove range" in {
        IndexTransformer.handleRemove(List(8), 5, 2) shouldBe List(6)
      }

      "Decrement indices that at the end of the remove range" in {
        IndexTransformer.handleRemove(List(7), 5, 2) shouldBe List(5)
      }
      
      "Decrement indices that at in the remove range" in {
        IndexTransformer.handleRemove(List(6), 5, 2) shouldBe List(5)
      }
      
      "Not decrement indices that at in the remove range" in {
        IndexTransformer.handleRemove(List(5), 5, 2) shouldBe List(5)
      }
      
      "Not decrement indices that are before the move range" in {
        IndexTransformer.handleRemove(List(4), 5, 2) shouldBe List(4)
      }

      "Properly transforms multiple items in a list against a remove" in {
        IndexTransformer.handleRemove(List(4, 5, 8, 12), 5, 4) shouldBe List(4, 5, 5, 8)
      }
    }
  }
}