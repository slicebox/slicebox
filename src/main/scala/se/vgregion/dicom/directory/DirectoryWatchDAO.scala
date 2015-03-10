package se.vgregion.dicom.directory

import scala.slick.driver.JdbcProfile
import java.nio.file.Path
import java.nio.file.Paths
import scala.slick.jdbc.meta.MTable
import se.vgregion.dicom.DicomProtocol._

class DirectoryWatchDAO(val driver: JdbcProfile) {
  import driver.simple._

  class DirectoryWatchDataTable(tag: Tag) extends Table[WatchedDirectory](tag, "DirectoryWatchData") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def path = column[String]("path")
    def * = (id, path) <> (WatchedDirectory.tupled, WatchedDirectory.unapply)
  }

  val watchedDirectoriesQuery = TableQuery[DirectoryWatchDataTable]

  def create(implicit session: Session) =
    if (MTable.getTables("DirectoryWatchData").list.isEmpty) {
      watchedDirectoriesQuery.ddl.create
    }
  
  def insert(watchedDirectory: WatchedDirectory)(implicit session: Session): WatchedDirectory = {
    val generatedId = (watchedDirectoriesQuery returning watchedDirectoriesQuery.map(_.id)) += watchedDirectory
    watchedDirectory.copy(id = generatedId)
  }

  def deleteWatchedDirectoryWithId(watchedDirectoryId: Long)(implicit session: Session): Int = {
    watchedDirectoriesQuery
      .filter(_.id === watchedDirectoryId)
      .delete
  }
  
  def allWatchedDirectories(implicit session: Session): List[WatchedDirectory] = watchedDirectoriesQuery.list
    
  def watchedDirectoryForId(id: Long)(implicit session: Session): Option[WatchedDirectory] =
    watchedDirectoriesQuery.filter(_.id === id).list.headOption
    
  def watchedDirectoryForPath(path: String)(implicit session: Session): Option[WatchedDirectory] =
    watchedDirectoriesQuery.filter(_.path === path).list.headOption
    
  private def toWatchedDirectory(watchedDirectory: WatchedDirectory) = WatchedDirectory(watchedDirectory.id, watchedDirectory.path)

}