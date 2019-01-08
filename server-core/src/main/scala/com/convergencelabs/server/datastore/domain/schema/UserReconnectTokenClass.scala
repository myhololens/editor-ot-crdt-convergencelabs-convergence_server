package com.convergencelabs.server.datastore.domain.schema

object UserReconnectTokenClass extends OrientDBClass {
  val ClassName = "UserReconnectToken"

  object Indices {
    val Token = "UserReconnectToken.token"
  }

  object Fields {
    val Token = "token"
    val User = "user"
    val ExpireTime = "expireTime"
  }
}