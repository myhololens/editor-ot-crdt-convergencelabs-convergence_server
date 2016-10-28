package com.convergencelabs.server.domain.model.ot

private[ot] object ObjectRemovePropertySetTF extends OperationTransformationFunction[ObjectRemovePropertyOperation, ObjectSetOperation] {
  def transform(s: ObjectRemovePropertyOperation, c: ObjectSetOperation): (ObjectRemovePropertyOperation, ObjectSetOperation) = {
    // O-RS-1
    (s.copy(noOp = true), c)
  }
}