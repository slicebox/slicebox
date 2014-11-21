package se.vgregion.db

import scala.slick.driver.JdbcProfile
import se.vgregion.dicom.ScpProtocol.ScpData

class ScpDataDAO(val driver: JdbcProfile) {
  import driver.simple._

  case class ScpDataRow(id: Long, name: String, aeTitle: String, port: Int, directory: String)

  class ScpDataTable(tag: Tag) extends Table[ScpDataRow](tag, "ScpData") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def aeTitle = column[String]("aeTitle")
    def port = column[Int]("port")
    def directory = column[String]("directory")
    def * = (id, name, aeTitle, port, directory) <> (ScpDataRow.tupled, ScpDataRow.unapply)
  }

  val props = TableQuery[ScpDataTable]

  def create(implicit session: Session) =
    props.ddl.create

  def insert(scpData: ScpData)(implicit session: Session) =
    props += ScpDataRow(-1, scpData.name, scpData.aeTitle, scpData.port, scpData.directory)

  def removeByName(name: String)(implicit session: Session) =
    props.filter(_.name === name).delete

  def list(implicit session: Session): List[ScpData] =
    props.list.map(row => ScpData(row.name, row.aeTitle, row.port, row.directory))

}