package com.convergencelabs.server.frontend.realtime

import org.json4s.JsonAST._
import com.google.protobuf.struct.Struct
import com.google.protobuf.struct.Value
import com.google.protobuf.struct.NullValue
import com.google.protobuf.struct.ListValue

object JsonProtoConverter {
  
  def toStruct(jsonObject: JObject): Struct = {
    toValue(jsonObject).getStructValue
  }
  
  def toValue(json: JValue): Value = {
    json match {
      case JNull =>
        Value().withNullValue(NullValue.NULL_VALUE)
      case JDouble(v) =>
        Value().withNumberValue(v)
      case JBool(v) =>
        Value().withBoolValue(v)
      case JString(v) =>
        Value().withStringValue(v)
      case JObject(fields) =>
        val mappedFields = fields.map {
          case (key, value) => (key, toValue(value))
        }
        Value().withStructValue(Struct().addAllFields(mappedFields))
      case JArray(values) =>
        val mappedValues = values.map(toValue(_))
        Value().withListValue(ListValue(mappedValues))
      case x: Any =>
        ???
    }
  }
}