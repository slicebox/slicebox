package se.vgregion.db

import scala.slick.driver.JdbcProfile
import se.vgregion.dicom.MetaDataProtocol.MetaData
import se.vgregion.dicom.MetaDataProtocol.Patient
import scala.collection.breakOut

class MetaDataDAO(val driver: JdbcProfile) {
  import driver.simple._

  case class MetaDataRow(
    id: Long,
    patientName: String,
    patientID: String,
    studyInstanceUID: String,
    studyDate: String,
    seriesInstanceUID: String,
    seriesDate: String,
    sopInstanceUID: String,
    fileName: String)

  class MetaDataTable(tag: Tag) extends Table[MetaDataRow](tag, "MetaData") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def patientName = column[String]("patientName")
    def patientID = column[String]("patientID")
    def studyInstanceUID = column[String]("studyInstanceUID")
    def studyDate = column[String]("studyDate")
    def seriesInstanceUID = column[String]("seriesInstanceUID")
    def seriesDate = column[String]("seriesDate")
    def sopInstanceUID = column[String]("sopInstanceUID")
    def fileName = column[String]("fileName")
    def * = (id, patientName, patientID, studyInstanceUID, studyDate, seriesInstanceUID, seriesDate, sopInstanceUID, fileName) <>
      (MetaDataRow.tupled, MetaDataRow.unapply)
  }

  val props = TableQuery[MetaDataTable]

  def create(implicit session: Session) = {
    props.ddl.create
  }

  def insert(metaData: MetaData)(implicit session: Session) = {
    props += MetaDataRow(
      -1,
      metaData.patientName,
      metaData.patientID,
      metaData.studyInstanceUID,
      metaData.studyDate,
      metaData.seriesInstanceUID,
      metaData.seriesDate,
      metaData.sopInstanceUID,
      metaData.fileName)
  }

  def list(implicit session: Session): List[MetaData] =
    props.list.map(row => MetaData(row.patientName, row.patientID, row.studyInstanceUID, row.studyDate, row.seriesInstanceUID, row.seriesDate, row.sopInstanceUID, row.fileName))

  def removeByFileName(fileName: String)(implicit session: Session): Int =
    props.filter(_.fileName === fileName).delete

  def listPatients(implicit session: Session): List[Patient] =
    list.map(metaData =>
      Patient(metaData.patientName, metaData.patientID)).groupBy(patient =>
      patient.patientName + patient.patientID).map(_._2.head)(breakOut)
}