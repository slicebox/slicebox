package se.vgregion.db

import scala.slick.driver.JdbcProfile
import se.vgregion.dicom.MetaDataProtocol._
import scala.collection.breakOut
import se.vgregion.lang.RichCollection.toRich
import se.vgregion.dicom.Attributes._

class MetaDataDAO(val driver: JdbcProfile) {
  import driver.simple._

  case class MetaDataRow(id: Long, imageFile: ImageFile)

  val toRow = (id: Long,
    patientName: String,
    patientID: String,
    patientBirthDate: String,
    patientSex: String,
    studyInstanceUID: String,
    studyDescription: String,
    studyDate: String,
    studyID: String,
    accessionNumber: String,
    manufacturer: String,
    stationName: String,
    frameOfReferenceUID: String,
    seriesInstanceUID: String,
    seriesDescription: String,
    seriesDate: String,
    modality: String,
    protocolName: String,
    bodyPartExamined: String,
    sopInstanceUID: String,
    imageType: String,
    fileName: String) => MetaDataRow(id, ImageFile(Image(Series(Study(Patient(
    PatientName(patientName), PatientID(patientID), PatientBirthDate(patientBirthDate), PatientSex(patientSex)),
    StudyInstanceUID(studyInstanceUID), StudyDescription(studyDescription), StudyDate(studyDate), StudyID(studyID), AccessionNumber(accessionNumber)),
    Equipment(Manufacturer(manufacturer), StationName(stationName)),
    FrameOfReference(FrameOfReferenceUID(frameOfReferenceUID)),
    SeriesInstanceUID(seriesInstanceUID), SeriesDescription(seriesDescription), SeriesDate(seriesDate), Modality(modality), ProtocolName(protocolName), BodyPartExamined(bodyPartExamined)),
    SOPInstanceUID(sopInstanceUID), ImageType(imageType)),
    FileName(fileName)))

  val fromRow = (d: MetaDataRow) => Option((d.id,
    d.imageFile.image.series.study.patient.patientName.value, d.imageFile.image.series.study.patient.patientID.value, d.imageFile.image.series.study.patient.patientBirthDate.value, d.imageFile.image.series.study.patient.patientSex.value,
    d.imageFile.image.series.study.studyInstanceUID.value, d.imageFile.image.series.study.studyDescription.value, d.imageFile.image.series.study.studyDate.value, d.imageFile.image.series.study.studyID.value, d.imageFile.image.series.study.accessionNumber.value,
    d.imageFile.image.series.equipment.manufacturer.value, d.imageFile.image.series.equipment.stationName.value, d.imageFile.image.series.frameOfReference.frameOfReferenceUID.value, d.imageFile.image.series.seriesInstanceUID.value, d.imageFile.image.series.seriesDescription.value, d.imageFile.image.series.seriesDate.value, d.imageFile.image.series.modality.value, d.imageFile.image.series.protocolName.value, d.imageFile.image.series.bodyPartExamined.value,
    d.imageFile.image.sopInstanceUID.value, d.imageFile.image.imageType.value,
    d.imageFile.fileName.value))

  class MetaDataTable(tag: Tag) extends Table[MetaDataRow](tag, "MetaData") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def patientName = column[String](PatientName.name)
    def patientID = column[String](PatientID.name)
    def patientBirthDate = column[String](PatientBirthDate.name)
    def patientSex = column[String](PatientSex.name)
    def studyInstanceUID = column[String](StudyInstanceUID.name)
    def studyDescription = column[String](StudyDescription.name)
    def studyDate = column[String](StudyDate.name)
    def studyID = column[String](StudyID.name)
    def accessionNumber = column[String](AccessionNumber.name)
    def manufacturer = column[String](Manufacturer.name)
    def stationName = column[String](StationName.name)
    def frameOfReferenceUID = column[String](FrameOfReferenceUID.name)
    def seriesInstanceUID = column[String](SeriesInstanceUID.name)
    def seriesDescription = column[String](SeriesDescription.name)
    def seriesDate = column[String](SeriesDate.name)
    def modality = column[String](Modality.name)
    def protocolName = column[String](ProtocolName.name)
    def bodyPartExamined = column[String](BodyPartExamined.name)
    def sopInstanceUID = column[String](SOPInstanceUID.name)
    def imageType = column[String](ImageType.name)
    def fileName = column[String]("FileName")
    def * = (id,
      patientName,
      patientID,
      patientBirthDate,
      patientSex,
      studyInstanceUID,
      studyDescription,
      studyDate,
      studyID,
      accessionNumber,
      manufacturer,
      stationName,
      frameOfReferenceUID,
      seriesInstanceUID,
      seriesDescription,
      seriesDate,
      modality,
      protocolName,
      bodyPartExamined,
      sopInstanceUID,
      imageType,
      fileName) <> (toRow.tupled, fromRow)
  }

  val props = TableQuery[MetaDataTable]

  def create(implicit session: Session) = {
    props.ddl.create
  }

  def insert(imageFile: ImageFile)(implicit session: Session) = props += MetaDataRow(-1, imageFile)

  def list(implicit session: Session): List[ImageFile] = props.list.map(row => row.imageFile)

  def removeByFileName(fileName: String)(implicit session: Session): Int =
    props
      .filter(_.fileName === fileName)
      .delete

  def listPatients(implicit session: Session): List[Patient] =
    list
      .map(_.image.series.study.patient)
      .distinctBy(patient => patient.patientName.value + patient.patientID.value)

  def listStudiesForPatient(patient: Patient)(implicit session: Session) =
    props
      .filter(_.patientName === patient.patientName.value)
      .filter(_.patientID === patient.patientID.value)
      .list
      .map(_.imageFile.image.series.study)
      .distinctBy(study => study.studyInstanceUID)

  def listSeriesForStudy(study: Study)(implicit session: Session) =
    props
      .filter(_.patientName === study.patient.patientName.value)
      .filter(_.patientID === study.patient.patientID.value)
      .filter(_.studyInstanceUID === study.studyInstanceUID.value)
      .list
      .map(_.imageFile.image.series)
      .distinctBy(series => series.seriesInstanceUID)

  def listImagesForSeries(series: Series)(implicit session: Session) =
    props
      .filter(_.patientName === series.study.patient.patientName.value)
      .filter(_.patientID === series.study.patient.patientID.value)
      .filter(_.studyInstanceUID === series.study.studyInstanceUID.value)
      .filter(_.seriesInstanceUID === series.seriesInstanceUID.value)
      .list
      .map(_.imageFile.image)
      .distinctBy(image => image.sopInstanceUID)

  def listImageFilesForImage(image: Image)(implicit session: Session) =
    props
      .filter(_.patientName === image.series.study.patient.patientName.value)
      .filter(_.patientID === image.series.study.patient.patientID.value)
      .filter(_.studyInstanceUID === image.series.study.studyInstanceUID.value)
      .filter(_.seriesInstanceUID === image.series.seriesInstanceUID.value)
      .filter(_.sopInstanceUID === image.sopInstanceUID.value)
      .list
      .map(_.imageFile)
}