package com.convergencelabs.server.datastore.domain.mapper

import scala.language.implicitConversions

import com.convergencelabs.server.datastore.mapper.ODocumentMapper
import com.convergencelabs.server.domain.model.data.BooleanValue
import com.orientechnologies.orient.core.record.impl.ODocument

object BooleanValueMapper extends ODocumentMapper {

  private[domain] implicit class BooleanValueToODocument(val obj: BooleanValue) extends AnyVal {
    def asODocument: ODocument = booleanValueToODocument(obj)
  }

  private[domain] implicit def booleanValueToODocument(obj: BooleanValue): ODocument = {
    val BooleanValue(vid, value) = obj
    val doc = new ODocument(OpDocumentClassName)
    doc.field(Fields.VID, vid)
    doc.field(Fields.Value, value)
    doc
  }

  private[domain] implicit class ODocumentToBooleanValue(val d: ODocument) extends AnyVal {
    def asBooleanValue: BooleanValue = oDocumentToBooleanValue(d)
  }

  private[domain] implicit def oDocumentToBooleanValue(doc: ODocument): BooleanValue = {
    validateDocumentClass(doc, DocumentClassName, OpDocumentClassName)

    val vid = doc.field(Fields.VID).asInstanceOf[String]
    val value = doc.field(Fields.Value).asInstanceOf[Boolean]
    BooleanValue(vid, value);
  }

  private[domain] val DocumentClassName = "BooleanValue"
  private[domain] val OpDocumentClassName = "BooleanOpValue"

  private[domain] object Fields {
    val VID = "vid"
    val Value = "value"
  }
}
