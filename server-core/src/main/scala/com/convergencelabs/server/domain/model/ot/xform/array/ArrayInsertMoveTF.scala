package com.convergencelabs.server.domain.model.ot

import MoveDirection.Backward
import MoveDirection.Forward
import MoveDirection.Identity
import RangeIndexRelationship.After
import RangeIndexRelationship.Before
import RangeIndexRelationship.End
import RangeIndexRelationship.Start
import RangeIndexRelationship.Within

/**
 * This transformation function handles a concurrent server
 * ArrayInsertOperation and a client ArrayMoveOperation.
 */
private[ot] object ArrayInsertMoveTF extends OperationTransformationFunction[ArrayInsertOperation, ArrayMoveOperation] {
  def transform(s: ArrayInsertOperation, c: ArrayMoveOperation): (ArrayInsertOperation, ArrayMoveOperation) = {
    ArrayMoveRangeHelper.getMoveDirection(c) match {
      case Forward => transformAgainstForwardMove(s, c)
      case Backward => transformAgainstBackwardMove(s, c)
      case Identity => transformAgainstIdentityMove(s, c)
    }
  }

  def transformAgainstForwardMove(s: ArrayInsertOperation, c: ArrayMoveOperation): (ArrayInsertOperation, ArrayMoveOperation) = {
    ArrayMoveRangeHelper.getRangeIndexRelationship(c, s.index) match {
      case Before | Start =>
        // A-IM-1 and A-IM-2
        (s, c.copy(fromIndex = c.fromIndex + 1, toIndex = c.toIndex + 1))
      case Within | End =>
        // A-IM-3 and A-IM-4
        (s.copy(index = s.index - 1), c.copy(toIndex = c.toIndex + 1))
      case After =>
        // A-IM-5
        (s, c)
    }
  }

  def transformAgainstBackwardMove(s: ArrayInsertOperation, c: ArrayMoveOperation): (ArrayInsertOperation, ArrayMoveOperation) = {
    ArrayMoveRangeHelper.getRangeIndexRelationship(c, s.index) match {
      case Before | Start =>
        // A-IM-6 and A-IM-7
        (s, c.copy(fromIndex = c.fromIndex + 1, toIndex = c.toIndex + 1))
      case Within | End =>
        // A-IM-8 and A-IM-9
        (s.copy(index = s.index + 1), c.copy(fromIndex = c.fromIndex + 1))
      case After =>
        // A-IM-10
        (s, c)
    }
  }

  private[this] def transformAgainstIdentityMove(s: ArrayInsertOperation, c: ArrayMoveOperation): (ArrayInsertOperation, ArrayMoveOperation) = {
    ArrayMoveRangeHelper.getRangeIndexRelationship(c, s.index) match {
      case After =>
        // A-IM-13
        (s, c)
      case _ =>
        // A-IM-11 and A-IM-12
        (s, c.copy(fromIndex = c.fromIndex + 1, toIndex = c.toIndex + 1))
    }
  }
}
