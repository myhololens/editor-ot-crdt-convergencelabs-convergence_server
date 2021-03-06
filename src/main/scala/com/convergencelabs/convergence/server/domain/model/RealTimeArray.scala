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

package com.convergencelabs.convergence.server.domain.model

import com.convergencelabs.convergence.server.domain.model.data.{ArrayValue, DataValue}
import com.convergencelabs.convergence.server.domain.model.ot._

import scala.util.{Failure, Success, Try}

class RealTimeArray(private[this] val value: ArrayValue,
                    private[this] val parent: Option[RealTimeContainerValue],
                    private[this] val parentField: Option[Any],
                    private[this] val valueFactory: RealTimeValueFactory)
  extends RealTimeContainerValue(value.id, parent, parentField, List()) {

  private[this] var childValues: List[RealTimeValue] = _

  this.setValue(value.children)

  def children: List[RealTimeValue] = {
    childValues
  }

  def valueAt(path: List[Any]): Option[RealTimeValue] = {
    path match {
      case Nil =>
        Some(this)
      case (index: Int) :: Nil =>
        childValues.lift(index)
      case (index: Int) :: rest =>
        childValues.lift(index).flatMap {
          case child: RealTimeContainerValue => child.valueAt(rest)
          case _ => None
        }
      case _ =>
        None
    }
  }

  def data(): List[_] = {
    children.map({ v => v.data() })
  }

  def dataValue(): ArrayValue = {
    ArrayValue(id, children map {
      _.dataValue()
    })
  }

  def child(childPath: Any): Try[Option[RealTimeValue]] = {
    childPath match {
      case index: Int =>
        Success(childValues.lift(index))
      case _ =>
        Failure(new IllegalArgumentException("Child path must be an Int for a RealTimeArray"))
    }
  }

  protected def processValidatedOperation(op: DiscreteOperation): Try[AppliedArrayOperation] = {
    op match {
      case insert: ArrayInsertOperation =>
        processInsertOperation(insert)
      case remove: ArrayRemoveOperation =>
        processRemoveOperation(remove)
      case replace: ArrayReplaceOperation =>
        processReplaceOperation(replace)
      case reorder: ArrayMoveOperation =>
        processReorderOperation(reorder)
      case value: ArraySetOperation =>
        processSetValueOperation(value)
      case _ =>
        Failure(new IllegalArgumentException("Invalid operation type for RealTimeArray: " + op))
    }
  }

  private[this] def processInsertOperation(op: ArrayInsertOperation): Try[AppliedArrayInsertOperation] = {
    // FIXME: validate index
    val ArrayInsertOperation(id, noOp, index, value) = op
    val child = this.valueFactory.createValue(value, Some(this), Some(parentField))
    childValues = childValues.patch(index, List(child), 0)
    this.updateIndices(index + 1, childValues.length - 1)

    Success(AppliedArrayInsertOperation(id, noOp, index, value))
  }

  private[this] def processRemoveOperation(op: ArrayRemoveOperation): Try[AppliedArrayRemoveOperation] = {
    val ArrayRemoveOperation(id, noOp, index) = op
    if (index >= childValues.size) {
      Failure(new IllegalArgumentException("Index out of bounds"))
    } else {
      val oldChild = childValues(index)
      childValues = childValues.patch(index, List(), 1)
      this.updateIndices(index, childValues.length - 1)

      oldChild.detach()

      Success(AppliedArrayRemoveOperation(id, noOp, index, Some(oldChild.dataValue())))
    }
  }

  private[this] def processReplaceOperation(op: ArrayReplaceOperation): Try[AppliedArrayReplaceOperation] = {
    val ArrayReplaceOperation(id, noOp, index, value) = op
    if (index >= childValues.size) {
      Failure(new IllegalArgumentException("Index out of bounds"))
    } else {
      val oldChild = childValues(index)
      val child = this.valueFactory.createValue(value, Some(this), Some(parentField))
      childValues = childValues.patch(index, List(child), 1)

      oldChild.detach()

      Success(AppliedArrayReplaceOperation(id, noOp, index, value, Some(oldChild.dataValue())))
    }
  }

  private[this] def processReorderOperation(op: ArrayMoveOperation): Try[AppliedArrayMoveOperation] = {
    val ArrayMoveOperation(id, noOp, fromIndex, toIndex) = op
    // FIXME validate toIndex
    if (fromIndex >= childValues.size) {
      Failure(new IllegalArgumentException("fromIndex out of bounds"))
    } else {
      val child = childValues(fromIndex)
      childValues = childValues.patch(fromIndex, List(), 1)
      childValues = childValues.patch(toIndex, List(child), 0)
      this.updateIndices(fromIndex, toIndex)

      Success(AppliedArrayMoveOperation(id, noOp, fromIndex, toIndex))
    }
  }

  private[this] def processSetValueOperation(op: ArraySetOperation): Try[AppliedArraySetOperation] = {
    val ArraySetOperation(id, noOp, value) = op
    val oldValue = dataValue()

    this.detachChildren()

    this.setValue(value)

    Success(AppliedArraySetOperation(id, noOp, value, Some(oldValue.children)))
  }

  private[this] def setValue(value: List[DataValue]): Unit = {
    var i = 0
    childValues = value.map {
      v => this.valueFactory.createValue(v, Some(this), Some({
        i += 1; i
      }))
    }
  }

  private[this] def updateIndices(fromIndex: Int, toIndex: Int): Unit = {
    for {i <- fromIndex to toIndex} {
      childValues(i).parentField = Some(i)
    }
  }

  override def detachChildren(): Unit = {
    childValues.foreach(_.detach())
  }
}
