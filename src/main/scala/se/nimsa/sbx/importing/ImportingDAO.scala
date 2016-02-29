package se.nimsa.sbx.importing

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.meta.MTable
import scala.slick.lifted.ProvenShape.proveShapeOf

import ImportingProtocol._

class ImportingDAO(val driver: JdbcProfile) {
  import driver.simple._
  val ImportSessionTableName = "ImportSessions"
  val ImportSessionImageTableName = "ImportSessionImages"
  
  class ImportSessionTable(tag: Tag) extends Table[ImportSession](tag, ImportSessionImageTableName) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name") 
    def filesImported = column[Int]("filesimported") 
    def filesRejected = column[Int]("filesrejected") 
    def created = column[Long]("created")
    def lastUpdated = column[Long]("lastupdated")
    def * = (id, name, filesImported, filesRejected, created, lastUpdated) <> (ImportSession.tupled, ImportSession.unapply)
  }

  val importSessionQuery = TableQuery[ImportSessionTable]
  
  class ImportSessionImageTable(tag: Tag) extends Table[ImportSessionImage](tag, ImportSessionImageTableName) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def importSessionId = column[Long]("importsessionid")
    def fkImportSession = foreignKey("fk_import_session_id", importSessionId, importSessionQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, importSessionId) <> (ImportSessionImage.tupled, ImportSessionImage.unapply)
  }

  val importSessionImageQuery = TableQuery[ImportSessionImageTable]
  
  def create(implicit session: Session): Unit = {
    if (MTable.getTables(ImportSessionTableName).list.isEmpty) importSessionQuery.ddl.create
    if (MTable.getTables(ImportSessionImageTableName).list.isEmpty) importSessionImageQuery.ddl.create
  }

  def drop(implicit session: Session): Unit =
    (importSessionQuery.ddl ++ importSessionImageQuery.ddl).drop

  def clear(implicit session: Session): Unit = {
    importSessionQuery.delete //Cascade deletes ImportSessionImages
  }
}