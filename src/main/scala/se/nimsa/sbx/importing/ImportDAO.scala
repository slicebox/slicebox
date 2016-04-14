package se.nimsa.sbx.importing

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.meta.MTable

import ImportProtocol._

class ImportDAO(val driver: JdbcProfile) {
  import driver.simple._
  val importSessionTableName = "ImportSessions"
  val importSessionImageTableName = "ImportSessionImages"

  class ImportSessionTable(tag: Tag) extends Table[ImportSession](tag, importSessionTableName) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def userId = column[Long]("userid")
    def user = column[String]("user")
    def filesImported = column[Int]("filesimported")
    def filesRejected = column[Int]("filesrejected")
    def created = column[Long]("created")
    def lastUpdated = column[Long]("lastupdated")
    def * = (id, name, userId, user, filesImported, filesRejected, created, lastUpdated) <> (ImportSession.tupled, ImportSession.unapply)
  }

  val importSessionQuery = TableQuery[ImportSessionTable]

  class ImportSessionImageTable(tag: Tag) extends Table[ImportSessionImage](tag, importSessionImageTableName) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def importSessionId = column[Long]("importsessionid")
    def imageId = column[Long]("imageid")
    def fkImportSession = foreignKey("fk_import_session_id", importSessionId, importSessionQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, importSessionId, imageId) <> (ImportSessionImage.tupled, ImportSessionImage.unapply)
  }

  val importSessionImageQuery = TableQuery[ImportSessionImageTable]

  def create(implicit session: Session): Unit = {
    if (MTable.getTables(importSessionTableName).list.isEmpty) importSessionQuery.ddl.create
    if (MTable.getTables(importSessionImageTableName).list.isEmpty) importSessionImageQuery.ddl.create
  }

  def drop(implicit session: Session): Unit =
    (importSessionQuery.ddl ++ importSessionImageQuery.ddl).drop

  def clear(implicit session: Session): Unit = {
    importSessionQuery.delete //Cascade deletes ImportSessionImages
  }

  def getImportSessions(implicit session: Session): List[ImportSession] = {
    importSessionQuery.list
  }

  def getImportSession(importSessionId: Long)(implicit session: Session): Option[ImportSession] = {
    importSessionQuery.filter(_.id === importSessionId).firstOption
  }

  def addImportSession(importSession: ImportSession)(implicit session: Session): ImportSession = {
    val generatedId = (importSessionQuery returning importSessionQuery.map(_.id)) += importSession
    importSession.copy(id = generatedId)
  }

  def removeImportSession(importSessionId: Long)(implicit session: Session): Unit =
    importSessionQuery.filter(_.id === importSessionId).delete

  def listImagesForImportSesstionId(importSessionId: Long)(implicit session: Session): List[ImportSessionImage] =
    importSessionImageQuery.filter(_.importSessionId === importSessionId).list

  def insertImportSessionImage(importSessionImage: ImportSessionImage)(implicit session: Session): ImportSessionImage = {
    val generatedId = (importSessionImageQuery returning importSessionImageQuery.map(_.id)) += importSessionImage
    importSessionImage.copy(id = generatedId)
  }
}