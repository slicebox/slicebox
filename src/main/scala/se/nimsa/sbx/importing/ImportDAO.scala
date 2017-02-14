/*
 * Copyright 2017 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.importing

import se.nimsa.sbx.importing.ImportProtocol._
import se.nimsa.sbx.util.DbUtil.createTables
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

class ImportDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import dbConf.profile.api._

  val db = dbConf.db

  class ImportSessionTable(tag: Tag) extends Table[ImportSession](tag, ImportSessionTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def userId = column[Long]("userid")
    def user = column[String]("user")
    def filesImported = column[Int]("filesimported")
    def filesAdded = column[Int]("filesadded")
    def filesRejected = column[Int]("filesrejected")
    def created = column[Long]("created")
    def lastUpdated = column[Long]("lastupdated")
    def idxUniqueName = index("idx_unique_import_session_name", name, unique = true)
    def * = (id, name, userId, user, filesImported, filesAdded, filesRejected, created, lastUpdated) <> (ImportSession.tupled, ImportSession.unapply)
  }

  object ImportSessionTable {
    val name = "ImportSessions"
  }

  val importSessionQuery = TableQuery[ImportSessionTable]

  class ImportSessionImageTable(tag: Tag) extends Table[ImportSessionImage](tag, ImportSessionImageTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def importSessionId = column[Long]("importsessionid")
    def imageId = column[Long]("imageid")
    def fkImportSession = foreignKey("fk_import_session_id", importSessionId, importSessionQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, importSessionId, imageId) <> (ImportSessionImage.tupled, ImportSessionImage.unapply)
  }

  object ImportSessionImageTable {
    val name = "ImportSessionImages"
  }

  val importSessionImageQuery = TableQuery[ImportSessionImageTable]

  def create() = createTables(dbConf, (ImportSessionTable.name, importSessionQuery), (ImportSessionImageTable.name, importSessionImageQuery))

  def drop() = db.run {
    (importSessionQuery.schema ++ importSessionImageQuery.schema).drop
  }

  def clear() = db.run {
    importSessionQuery.delete //Cascade deletes ImportSessionImages
  }

  def getImportSessions(startIndex: Long, count: Long): Future[Seq[ImportSession]] = db.run {
    importSessionQuery
      .drop(startIndex)
      .take(count)
      .result
  }

  def getImportSession(importSessionId: Long): Future[Option[ImportSession]] = db.run {
    importSessionQuery.filter(_.id === importSessionId).result.headOption
  }

  def addImportSession(importSession: ImportSession): Future[ImportSession] = db.run {
    (importSessionQuery returning importSessionQuery.map(_.id) += importSession)
      .map(generatedId => importSession.copy(id = generatedId))
  }

  def removeImportSession(importSessionId: Long): Future[Unit] = db.run {
    importSessionQuery.filter(_.id === importSessionId).delete.map(_ => {})
  }

  def importSessionForName(importSessionName: String): Future[Option[ImportSession]] = db.run {
    importSessionQuery.filter(_.name === importSessionName).result.headOption
  }

  def listImagesForImportSessionId(importSessionId: Long): Future[Seq[ImportSessionImage]] = db.run {
    importSessionImageQuery.filter(_.importSessionId === importSessionId).result
  }

  def importSessionImageForImportSessionIdAndImageId(importSessionId: Long, imageId: Long): Future[Option[ImportSessionImage]] = db.run {
    importSessionImageQuery
      .filter(_.importSessionId === importSessionId)
      .filter(_.imageId === imageId)
      .result
      .headOption
  }

  def insertImportSessionImage(importSessionImage: ImportSessionImage): Future[ImportSessionImage] = db.run {
    (importSessionImageQuery returning importSessionImageQuery.map(_.id) += importSessionImage)
      .map(generatedId => importSessionImage.copy(id = generatedId))
  }

  def updateImportSession(importSession: ImportSession): Future[ImportSession] = db.run {
    importSessionQuery.filter(_.id === importSession.id).update(importSession)
      .map(_ => importSession)
  }
}
