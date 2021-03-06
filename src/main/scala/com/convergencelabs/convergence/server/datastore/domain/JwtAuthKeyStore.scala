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

package com.convergencelabs.convergence.server.datastore.domain

import java.time.Instant
import java.util.Date

import com.convergencelabs.convergence.server.datastore.{AbstractDatabasePersistence, DuplicateValueException, OrientDBUtil}
import com.convergencelabs.convergence.server.datastore.domain.schema.JwtAuthKeyClass.{ClassName, Fields, Indices}
import com.convergencelabs.convergence.server.db.DatabaseProvider
import com.convergencelabs.convergence.server.domain.JwtAuthKey
import com.orientechnologies.orient.core.record.impl.ODocument
import com.orientechnologies.orient.core.storage.ORecordDuplicatedException
import grizzled.slf4j.Logging

import scala.util.{Failure, Try}

object JwtAuthKeyStore {

  case class KeyInfo(id: String, description: String, key: String, enabled: Boolean)

  def jwtAuthKeyToDoc(jwtAuthKey: JwtAuthKey): ODocument = {
    val doc = new ODocument(ClassName)
    doc.setProperty(Fields.Id, jwtAuthKey.id)
    doc.setProperty(Fields.Description, jwtAuthKey.description)
    doc.setProperty(Fields.Updated, new Date(jwtAuthKey.updated.toEpochMilli))
    doc.setProperty(Fields.Key, jwtAuthKey.key)
    doc.setProperty(Fields.Enabled, jwtAuthKey.enabled)
    doc
  }

  def docToJwtAuthKey(doc: ODocument): JwtAuthKey = {
    val createdDate: Date = doc.getProperty(Fields.Updated)
    JwtAuthKey(
      doc.getProperty(Fields.Id),
      doc.getProperty(Fields.Description),
      Instant.ofEpochMilli(createdDate.getTime),
      doc.getProperty(Fields.Key),
      doc.getProperty(Fields.Enabled))
  }
}

class JwtAuthKeyStore private[datastore] (
  private[this] val dbProvider: DatabaseProvider)
  extends AbstractDatabasePersistence(dbProvider)
  with Logging {

  import JwtAuthKeyStore._

  val GetKeysQuery = "SELECT * FROM JwtAuthKey ORDER BY id ASC"
  def getKeys(offset: Option[Int], limit: Option[Int]): Try[List[JwtAuthKey]] = withDb { db =>
    val query = OrientDBUtil.buildPagedQuery(GetKeysQuery, limit, offset)
    OrientDBUtil
      .query(db, query)
      .map(_.map(docToJwtAuthKey))
  }

  private[this] val GetKeyQuery = "SELECT * FROM JwtAuthKey WHERE id = :id"
  def getKey(id: String): Try[Option[JwtAuthKey]] = withDb { db =>
    val params = Map(Fields.Id -> id)
    OrientDBUtil
      .findDocument(db, GetKeyQuery, params)
      .map(_.map(docToJwtAuthKey))
  }

  def createKey(key: KeyInfo): Try[Unit] = {
    val KeyInfo(id, description, publicKey, enabled) = key
    val jwtAuthKey = JwtAuthKey(id, description, Instant.now(), publicKey, enabled)
    importKey(jwtAuthKey)
  }

  def importKey(jwtAuthKey: JwtAuthKey): Try[Unit] = tryWithDb { db =>
    val doc = JwtAuthKeyStore.jwtAuthKeyToDoc(jwtAuthKey)
    db.save(doc)
    ()
  } recoverWith (handleDuplicateValue)

  private[this] val UpdateKeyQuery = "SELECT FROM JwtAuthKey WHERE id = :id"
  def updateKey(info: KeyInfo): Try[Unit] = withDb { db =>
    val KeyInfo(keyId, descr, key, enabled) = info
    val updateKey = JwtAuthKey(keyId, descr, Instant.now(), key, enabled)
    val updatedDoc = JwtAuthKeyStore.jwtAuthKeyToDoc(updateKey)
    val params = Map(Fields.Id -> keyId)
    OrientDBUtil
      .getDocument(db, UpdateKeyQuery, params)
      .flatMap { doc =>
        Try {
          doc.merge(updatedDoc, false, false)
          db.save(doc)
          ()
        }
      }
  }

  private[this] val DeleteKeyCommand = "DELETE FROM JwtAuthKey WHERE id = :id"
  def deleteKey(id: String): Try[Unit] = withDb { db =>
    val params = Map(Fields.Id -> id)
     OrientDBUtil
      .mutateOneDocument(db, DeleteKeyCommand, params)
  }

  private[this] def handleDuplicateValue[T](): PartialFunction[Throwable, Try[T]] = {
    case e: ORecordDuplicatedException =>
      e.getIndexName match {
        case Indices.Id =>
          Failure(DuplicateValueException(Fields.Id))
        case _ =>
          Failure(e)
      }
  }
}
