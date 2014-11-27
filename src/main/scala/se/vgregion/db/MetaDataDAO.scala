package se.vgregion.db

import scala.slick.driver.JdbcProfile
import se.vgregion.dicom.MetaDataProtocol._
import scala.collection.breakOut
import se.vgregion.lang.RichCollection.toRich

class MetaDataDAO(val driver: JdbcProfile) {
  import driver.simple._

  case class MetaDataRow(
    id: Long,
    patientName: String,
    patientID: String,
    studyDate: String,
    studyInstanceUID: String,
    seriesDate: String,
    seriesInstanceUID: String,
    sopInstanceUID: String,
    fileName: String)

  class MetaDataTable(tag: Tag) extends Table[MetaDataRow](tag, "MetaData") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def patientName = column[String]("patientName")
    def patientID = column[String]("patientID")
    def studyDate = column[String]("studyDate")
    def studyInstanceUID = column[String]("studyInstanceUID")
    def seriesDate = column[String]("seriesDate")
    def seriesInstanceUID = column[String]("seriesInstanceUID")
    def sopInstanceUID = column[String]("sopInstanceUID")
    def fileName = column[String]("fileName")
    def * = (id, patientName, patientID, studyDate, studyInstanceUID, seriesDate, seriesInstanceUID, sopInstanceUID, fileName) <>
      (MetaDataRow.tupled, MetaDataRow.unapply)
  }

  val props = TableQuery[MetaDataTable]

  def create(implicit session: Session) = {
    props.ddl.create
  }

  def insert(image: Image)(implicit session: Session) = {
    props += MetaDataRow(
      -1,
      image.series.study.patient.patientName,
      image.series.study.patient.patientID,
      image.series.study.studyDate,
      image.series.study.studyInstanceUID,
      image.series.seriesDate,
      image.series.seriesInstanceUID,
      image.sopInstanceUID,
      image.fileName)
  }

  def list(implicit session: Session): List[Image] =
    props
      .list
      .map(row => Image(Series(Study(Patient(row.patientName, row.patientID), row.studyDate, row.studyInstanceUID), row.seriesDate, row.seriesInstanceUID), row.sopInstanceUID, row.fileName))

  def removeByFileName(fileName: String)(implicit session: Session): Int =
    props
      .filter(_.fileName === fileName)
      .delete

  def listPatients(implicit session: Session): List[Patient] =
    list
      .map(_.series.study.patient)
      .distinctBy(patient => patient.patientName + patient.patientID)

  def listStudiesForPatient(patient: Patient)(implicit session: Session) =
    props
      .filter(_.patientName === patient.patientName)
      .filter(_.patientID === patient.patientID)
      .list
      .map(row => Study(patient, row.studyDate, row.studyInstanceUID))
      .distinctBy(study => study.studyInstanceUID + study.studyDate)

  def listSeriesForStudy(study: Study)(implicit session: Session) =
    props
      .filter(_.patientName === study.patient.patientName)
      .filter(_.patientID === study.patient.patientID)
      .filter(_.studyDate === study.studyDate)
      .filter(_.studyInstanceUID === study.studyInstanceUID)
      .list
      .map(row => Series(study, row.seriesDate, row.seriesInstanceUID))
      .distinctBy(series => series.seriesInstanceUID + series.seriesDate)

  def listImagesForSeries(series: Series)(implicit session: Session) =
    props
      .filter(_.patientName === series.study.patient.patientName)
      .filter(_.patientID === series.study.patient.patientID)
      .filter(_.studyDate === series.study.studyDate)
      .filter(_.studyInstanceUID === series.study.studyInstanceUID)
      .filter(_.seriesDate === series.seriesDate)
      .filter(_.seriesInstanceUID === series.seriesInstanceUID)
      .list
      .map(row => Image(series, row.sopInstanceUID, row.fileName))
      .distinctBy(image => image.fileName + image.sopInstanceUID)

}