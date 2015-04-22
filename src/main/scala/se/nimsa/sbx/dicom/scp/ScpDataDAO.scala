package se.nimsa.sbx.dicom.scp

import scala.slick.driver.JdbcProfile
import se.nimsa.sbx.dicom.DicomProtocol.ScpData
import scala.slick.jdbc.meta.MTable

class ScpDataDAO(val driver: JdbcProfile) {
  import driver.simple._

  class ScpDataTable(tag: Tag) extends Table[ScpData](tag, "ScpData") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def aeTitle = column[String]("aeTitle")
    def port = column[Int]("port")
    def * = (id, name, aeTitle, port) <> (ScpData.tupled, ScpData.unapply)
  }

  val scpDataQuery = TableQuery[ScpDataTable]
  
  def create(implicit session: Session) =
    if (MTable.getTables("ScpData").list.isEmpty) {
      scpDataQuery.ddl.create
    }
  
  
  def insert(scpData: ScpData)(implicit session: Session): ScpData = {
    val generatedId = (scpDataQuery returning scpDataQuery.map(_.id)) += scpData
    scpData.copy(id = generatedId)
  }

  def deleteScpDataWithId(scpDataId: Long)(implicit session: Session): Int = {
    scpDataQuery
      .filter(_.id === scpDataId)
      .delete
  }
  
  def scpDataForId(id: Long)(implicit session: Session): Option[ScpData] =
    scpDataQuery.filter(_.id === id).list.headOption
  
  def scpDataForName(name: String)(implicit session: Session): Option[ScpData] =
    scpDataQuery.filter(_.name === name).list.headOption
  
  def scpDataForPort(port: Int)(implicit session: Session): Option[ScpData] =
    scpDataQuery.filter(_.port === port).list.headOption
  
  def allScpDatas(implicit session: Session): List[ScpData] = scpDataQuery.list
}