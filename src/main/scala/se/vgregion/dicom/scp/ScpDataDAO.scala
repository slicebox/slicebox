package se.vgregion.dicom.scp

import scala.slick.driver.JdbcProfile
import se.vgregion.dicom.DicomDispatchProtocol.ScpData
import scala.slick.jdbc.meta.MTable

class ScpDataDAO(val driver: JdbcProfile) {
  import driver.simple._

  case class ScpDataRow(key: Long, name: String, aeTitle: String, port: Int)

  class ScpDataTable(tag: Tag) extends Table[ScpDataRow](tag, "ScpData") {
    def key = column[Long]("key", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def aeTitle = column[String]("aeTitle")
    def port = column[Int]("port")
    def * = (key, name, aeTitle, port) <> (ScpDataRow.tupled, ScpDataRow.unapply)
  }

  val props = TableQuery[ScpDataTable]

  def create(implicit session: Session) =
    if (MTable.getTables("ScpData").list.isEmpty) {
      props.ddl.create
    }
  
  def insert(scpData: ScpData)(implicit session: Session) =
    props += ScpDataRow(-1, scpData.name, scpData.aeTitle, scpData.port)

  def removeByName(name: String)(implicit session: Session) =
    props.filter(_.name === name).delete

  def list(implicit session: Session): List[ScpData] =
    props.list.map(row => ScpData(row.name, row.aeTitle, row.port))

}