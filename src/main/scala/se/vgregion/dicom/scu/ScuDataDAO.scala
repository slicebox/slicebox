package se.vgregion.dicom.scu

import scala.slick.driver.JdbcProfile
import se.vgregion.dicom.DicomProtocol.ScuData
import scala.slick.jdbc.meta.MTable

class ScuDataDAO(val driver: JdbcProfile) {
  import driver.simple._

  class ScuDataTable(tag: Tag) extends Table[ScuData](tag, "ScuData") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def aeTitle = column[String]("aeTitle")
    def host = column[String]("host")
    def port = column[Int]("port")
    def * = (id, name, aeTitle, host, port) <> (ScuData.tupled, ScuData.unapply)
  }

  val scuDataQuery = TableQuery[ScuDataTable]
  
  def create(implicit session: Session) =
    if (MTable.getTables("ScuData").list.isEmpty) {
      scuDataQuery.ddl.create
    }
  
  
  def insert(scuData: ScuData)(implicit session: Session): ScuData = {
    val generatedId = (scuDataQuery returning scuDataQuery.map(_.id)) += scuData
    scuData.copy(id = generatedId)
  }

  def deleteScuDataWithId(scuDataId: Long)(implicit session: Session): Int = {
    scuDataQuery
      .filter(_.id === scuDataId)
      .delete
  }
  
  def scuDataForId(id: Long)(implicit session: Session): Option[ScuData] =
    scuDataQuery.filter(_.id === id).list.headOption
  
  def scuDataForName(name: String)(implicit session: Session): Option[ScuData] =
    scuDataQuery.filter(_.name === name).list.headOption
  
  def scuDataForHostAndPort(host: String, port: Int)(implicit session: Session): Option[ScuData] =
    scuDataQuery.filter(_.host === host).filter(_.port === port).list.headOption
  
  def allScuDatas(implicit session: Session): List[ScuData] = scuDataQuery.list
}