package com.convergencelabs.server.domain.model.ot

private[ot] object StringSetRemoveTF extends OperationTransformationFunction[StringSetOperation, StringRemoveOperation] {
  def transform(s: StringSetOperation, c: StringRemoveOperation): (StringOperation, StringOperation) = {
    // S-SR-1
    (s, c.copy(noOp = true))
  }
}