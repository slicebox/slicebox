package se.vgregion.dicom.directory

import scala.slick.driver.JdbcProfile
import java.nio.file.Path
import java.nio.file.Paths
import scala.slick.jdbc.meta.MTable

class DirectoryWatchDAO(val driver: JdbcProfile) {
  import driver.simple._

  case class DirectoryWatchDataRow(key: Long, pathName: String)

  class DirectoryWatchDataTable(tag: Tag) extends Table[DirectoryWatchDataRow](tag, "DirectoryWatchData") {
    def key = column[Long]("key", O.PrimaryKey, O.AutoInc)
    def pathName = column[String]("pathName")
    def * = (key, pathName) <> (DirectoryWatchDataRow.tupled, DirectoryWatchDataRow.unapply)
  }

  val props = TableQuery[DirectoryWatchDataTable]

  def create(implicit session: Session) =
    if (MTable.getTables("DirectoryWatchData").list.isEmpty) {
      props.ddl.create
    }

  def insert(path: Path)(implicit session: Session) =
    props += DirectoryWatchDataRow(-1, path.toAbsolutePath().toString())

  def remove(path: Path)(implicit session: Session) =
    props.filter(_.pathName === path.toAbsolutePath().toString()).delete

  def list(implicit session: Session): List[Path] =
    props.list.map(row => Paths.get(row.pathName))

}