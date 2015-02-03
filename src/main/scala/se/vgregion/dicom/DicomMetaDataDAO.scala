package se.vgregion.dicom

import scala.slick.driver.JdbcProfile
import DicomProtocol.FileName
import DicomProtocol.ImageFile
import DicomHierarchy._
import DicomPropertyValue._
import scala.slick.jdbc.meta.MTable

class DicomMetaDataDAO(val driver: JdbcProfile) {
  import driver.simple._

  // *** Patient *** 

  private val toPatient = (id: Long, patientName: String, patientID: String, patientBirthDate: String, patientSex: String) =>
    Patient(id, PatientName(patientName), PatientID(patientID), PatientBirthDate(patientBirthDate), PatientSex(patientSex))

  private val fromPatient = (patient: Patient) => Option((patient.id, patient.patientName.value, patient.patientID.value, patient.patientBirthDate.value, patient.patientSex.value))

  private class Patients(tag: Tag) extends Table[Patient](tag, "Patients") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def patientName = column[String](DicomProperty.PatientName.name)
    def patientID = column[String](DicomProperty.PatientID.name)
    def patientBirthDate = column[String](DicomProperty.PatientBirthDate.name)
    def patientSex = column[String](DicomProperty.PatientSex.name)
    def * = (id, patientName, patientID, patientBirthDate, patientSex) <> (toPatient.tupled, fromPatient)
  }

  private val patientsQuery = TableQuery[Patients]

  private val fromStudy = (study: Study) => Option((study.id, study.patientId, study.studyInstanceUID.value, study.studyDescription.value, study.studyDate.value, study.studyID.value, study.accessionNumber.value))
  
  // *** Study *** //

  private val toStudy = (id: Long, patientId: Long, studyInstanceUID: String, studyDescription: String, studyDate: String, studyID: String, accessionNumber: String) =>
    Study(id, patientId, StudyInstanceUID(studyInstanceUID), StudyDescription(studyDescription), StudyDate(studyDate), StudyID(studyID), AccessionNumber(accessionNumber))

  private class Studies(tag: Tag) extends Table[Study](tag, "Studies") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def patientId = column[Long]("patientId")
    def studyInstanceUID = column[String](DicomProperty.StudyInstanceUID.name)
    def studyDescription = column[String](DicomProperty.StudyDescription.name)
    def studyDate = column[String](DicomProperty.StudyDate.name)
    def studyID = column[String](DicomProperty.StudyID.name)
    def accessionNumber = column[String](DicomProperty.AccessionNumber.name)
    def * = (id, patientId, studyInstanceUID, studyDescription, studyDate, studyID, accessionNumber) <> (toStudy.tupled, fromStudy)

    def patientFKey = foreignKey("patientFKey", patientId, patientsQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def patientIdJoin = patientsQuery.filter(_.id === patientId)
  }

  private val studiesQuery = TableQuery[Studies]
  
  // *** Equipment ***

  private val toEquipment = (id: Long, manufacturer: String, stationName: String) =>
    Equipment(id, Manufacturer(manufacturer), StationName(stationName))

  private val fromEquipment = (equipment: Equipment) => Option((equipment.id, equipment.manufacturer.value, equipment.stationName.value))

  private class Equipments(tag: Tag) extends Table[Equipment](tag, "Equipments") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def manufacturer = column[String](DicomProperty.Manufacturer.name)
    def stationName = column[String](DicomProperty.StationName.name)
    def * = (id, manufacturer, stationName) <> (toEquipment.tupled, fromEquipment)
  }

  private val equipmentsQuery = TableQuery[Equipments]

  // *** Frame of Reference ***

  private val toFrameOfReference = (id: Long, frameOfReferenceUID: String) =>
    FrameOfReference(id, FrameOfReferenceUID(frameOfReferenceUID))

  private val fromFrameOfReference = (frameOfReference: FrameOfReference) => Option((frameOfReference.id, frameOfReference.frameOfReferenceUID.value))

  private class FrameOfReferences(tag: Tag) extends Table[FrameOfReference](tag, "FrameOfReferences") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def frameOfReferenceUID = column[String](DicomProperty.FrameOfReferenceUID.name)
    def * = (id, frameOfReferenceUID) <> (toFrameOfReference.tupled, fromFrameOfReference)
  }

  private val frameOfReferencesQuery = TableQuery[FrameOfReferences]

  // *** Series ***

  private val toSeries = (id: Long, studyId: Long, equipmentId: Long, frameOfReferenceId: Long, seriesInstanceUID: String, seriesDescription: String, seriesDate: String, modality: String, protocolName: String, bodyPartExamined: String) =>
    Series(id, studyId, equipmentId, frameOfReferenceId, SeriesInstanceUID(seriesInstanceUID), SeriesDescription(seriesDescription), SeriesDate(seriesDate), Modality(modality), ProtocolName(protocolName), BodyPartExamined(bodyPartExamined))

  private val fromSeries = (series: Series) => Option((series.id, series.studyId, series.equipmentId, series.frameOfReferenceId, series.seriesInstanceUID.value, series.seriesDescription.value, series.seriesDate.value, series.modality.value, series.protocolName.value, series.bodyPartExamined.value))

  private class SeriesTable(tag: Tag) extends Table[Series](tag, "Series") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def studyId = column[Long]("studyId")
    def equipmentId = column[Long]("equipmentId")
    def frameOfReferenceId = column[Long]("frameOfReferenceId")
    def seriesInstanceUID = column[String](DicomProperty.SeriesInstanceUID.name)
    def seriesDescription = column[String](DicomProperty.SeriesDescription.name)
    def seriesDate = column[String](DicomProperty.SeriesDate.name)
    def modality = column[String](DicomProperty.Modality.name)
    def protocolName = column[String](DicomProperty.ProtocolName.name)
    def bodyPartExamined = column[String](DicomProperty.BodyPartExamined.name)
    def * = (id, studyId, equipmentId, frameOfReferenceId, seriesInstanceUID, seriesDescription, seriesDate, modality, protocolName, bodyPartExamined) <> (toSeries.tupled, fromSeries)

    def studyFKey = foreignKey("studyFKey", studyId, studiesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def equipmentFKey = foreignKey("equipmentFKey", equipmentId, equipmentsQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def frameOfReferenceFKey = foreignKey("frameOfReferenceFKey", frameOfReferenceId, frameOfReferencesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def studyIdJoin = studiesQuery.filter(_.id === studyId)
  }

  private val seriesQuery = TableQuery[SeriesTable]

  // *** Image ***

  private val toImage = (id: Long, seriesId: Long, sopInstanceUID: String, imageType: String) =>
    Image(id, seriesId, SOPInstanceUID(sopInstanceUID), ImageType(imageType))

  private val fromImage = (image: Image) => Option((image.id, image.seriesId, image.sopInstanceUID.value, image.imageType.value))

  private class Images(tag: Tag) extends Table[Image](tag, "Images") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def seriesId = column[Long]("seriesId")
    def sopInstanceUID = column[String](DicomProperty.SOPInstanceUID.name)
    def imageType = column[String](DicomProperty.ImageType.name)
    def * = (id, seriesId, sopInstanceUID, imageType) <> (toImage.tupled, fromImage)

    def seriesFKey = foreignKey("seriesFKey", seriesId, seriesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def seriesIdJoin = seriesQuery.filter(_.id === seriesId)
  }

  private val imagesQuery = TableQuery[Images]

  // *** Files ***

  private val toImageFile = (imageId: Long, fileName: String) => ImageFile(imageId, FileName(fileName))

  private val fromImageFile = (imageFile: ImageFile) => Option((imageFile.id, imageFile.fileName.value))

  private class ImageFiles(tag: Tag) extends Table[ImageFile](tag, "ImageFiles") {
    def id = column[Long]("id", O.PrimaryKey)
    def fileName = column[String]("fileName")
    def * = (id, fileName) <> (toImageFile.tupled, fromImageFile)

    def imageFKey = foreignKey("imageFKey", id, imagesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def imageIdJoin = imagesQuery.filter(_.id === id)
  }

  private val imageFilesQuery = TableQuery[ImageFiles]

  def create(implicit session: Session) =
    if (MTable.getTables("Patients").list.isEmpty)
      (patientsQuery.ddl ++
        studiesQuery.ddl ++
        equipmentsQuery.ddl ++
        frameOfReferencesQuery.ddl ++
        seriesQuery.ddl ++
        imagesQuery.ddl ++
        imageFilesQuery.ddl).create

  // *** Get entities by id

  def patientById(id: Long)(implicit session: Session): Option[Patient] =
    patientsQuery.filter(_.id === id).list.headOption

  def studyBtId(id: Long)(implicit session: Session): Option[Study] =
    studiesQuery.filter(_.id === id).list.headOption
    
  def seriesById(id: Long)(implicit session: Session): Option[Series] =
    seriesQuery.filter(_.id === id).list.headOption

  def equipmentById(id: Long)(implicit session: Session): Option[Equipment] =
    equipmentsQuery.filter(_.id === id).list.headOption

  def frameOfReferenceById(id: Long)(implicit session: Session): Option[FrameOfReference] =
    frameOfReferencesQuery.filter(_.id === id).list.headOption
    
  def imageById(id: Long)(implicit session: Session): Option[Image] =
    imagesQuery.filter(_.id === id).list.headOption
    
  def imageFileById(imageId: Long)(implicit session: Session): Option[ImageFile] =
    imageFilesQuery.filter(_.id === imageId).list.headOption

  // *** Inserts ***

  def insert(patient: Patient)(implicit session: Session): Patient = {
    val generatedId = (patientsQuery returning patientsQuery.map(_.id)) += patient
    patient.copy(id = generatedId)
  }
  
  def insert(study: Study)(implicit session: Session): Study = {
    val generatedId = (studiesQuery returning studiesQuery.map(_.id)) += study
    study.copy(id = generatedId)
  }
  
  def insert(series: Series)(implicit session: Session): Series = {
    val generatedId = (seriesQuery returning seriesQuery.map(_.id)) += series
    series.copy(id = generatedId)
  }
  
  def insert(frameOfReference: FrameOfReference)(implicit session: Session): FrameOfReference = {
    val generatedId = (frameOfReferencesQuery returning frameOfReferencesQuery.map(_.id)) += frameOfReference
    frameOfReference.copy(id = generatedId)
  }
  
  def insert(equipment: Equipment)(implicit session: Session): Equipment = {
    val generatedId = (equipmentsQuery returning equipmentsQuery.map(_.id)) += equipment
    equipment.copy(id = generatedId)
  }
  
  def insert(image: Image)(implicit session: Session): Image = {
    val generatedId = (imagesQuery returning imagesQuery.map(_.id)) += image
    image.copy(id = generatedId)
  }
  
  def insert(imageFile: ImageFile)(implicit session: Session): ImageFile = {
    imageFilesQuery += imageFile
    imageFile
  }

  // *** Listing all patients, studies etc ***

  def patients(implicit session: Session): List[Patient] = patientsQuery.list
  
  def studies(implicit session: Session): List[Study] = studiesQuery.list
  
  def series(implicit session: Session): List[Series] = seriesQuery.list

  def equipments(implicit session: Session): List[Equipment] = equipmentsQuery.list

  def frameOfReferences(implicit session: Session): List[FrameOfReference] = frameOfReferencesQuery.list

  def images(implicit session: Session): List[Image] = imagesQuery.list

  def imageFiles(implicit session: Session): List[ImageFile] = imageFilesQuery.list

  // *** Grouped listings ***

  def studiesForPatient(patientId: Long)(implicit session: Session): List[Study] =
    studiesQuery
      .filter(_.patientId === patientId)
      .list

  def seriesForStudy(studyId: Long)(implicit session: Session): List[Series] =
      seriesQuery
        .filter(_.studyId === studyId)
        .list

  def imagesForSeries(seriesId: Long)(implicit session: Session): List[Image] =
      imagesQuery
        .filter(_.seriesId === seriesId)
        .list

  def imageFileForImage(imageId: Long)(implicit session: Session): Option[ImageFile] =
      imageFilesQuery
        .filter(_.id === imageId)
        .list.headOption

  def imageFilesForSeries(seriesId: Long)(implicit session: Session): List[ImageFile] =
    imagesForSeries(seriesId)
      .map(image => imageFileForImage(image.id)).flatten

  def imageFilesForStudy(studyId: Long)(implicit session: Session): List[ImageFile] =
    seriesForStudy(studyId)
      .map(series => imagesForSeries(series.id)
        .map(image => imageFileForImage(image.id)).flatten).flatten

  def imageFilesForPatient(patientId: Long)(implicit session: Session): List[ImageFile] =
    studiesForPatient(patientId)
      .map(study => seriesForStudy(study.id)
        .map(series => imagesForSeries(series.id)
          .map(image => imageFileForImage(image.id)).flatten).flatten).flatten
          
  def patientByNameAndID(patient: Patient)(implicit session: Session): Option[Patient] =
    patientsQuery
      .filter(_.patientName === patient.patientName.value)
      .filter(_.patientID === patient.patientID.value)
      .list.headOption
      
  def studyByUid(study: Study)(implicit session: Session): Option[Study] =
    studiesQuery
      .filter(_.studyInstanceUID === study.studyInstanceUID.value)
      .list.headOption
      
  def equipmentByManufacturerAndStationName(equipment: Equipment)(implicit session: Session): Option[Equipment] =
    equipmentsQuery
      .filter(_.manufacturer === equipment.manufacturer.value)
      .filter(_.stationName === equipment.stationName.value)
      .list.headOption
      
  def frameOfReferenceByUid(frameOfReference: FrameOfReference)(implicit session: Session): Option[FrameOfReference] =
    frameOfReferencesQuery
      .filter(_.frameOfReferenceUID === frameOfReference.frameOfReferenceUID.value)
      .list.headOption

  def seriesByUid(series: Series)(implicit session: Session): Option[Series] =
    seriesQuery
      .filter(_.seriesInstanceUID === series.seriesInstanceUID.value)
      .list.headOption
      
  def imageByUid(image: Image)(implicit session: Session): Option[Image] =
    imagesQuery
      .filter(_.sopInstanceUID === image.sopInstanceUID.value)
      .list.headOption
      
  def imageFileByFileName(imageFile: ImageFile)(implicit session: Session): Option[ImageFile] =
    imageFilesQuery
      .filter(_.fileName === imageFile.fileName.value)
      .list.headOption
      
  // *** Deletes ***

  def deletePatient(patientId: Long)(implicit session: Session): Int = {
    patientsQuery
      .filter(_.id === patientId)
      .delete
  }
  
  def deleteStudy(studyId: Long)(implicit session: Session): Int = {
    studiesQuery
      .filter(_.id === studyId)
      .delete
  }
  
  def deleteSeries(seriesId: Long)(implicit session: Session): Int = {
    seriesQuery
      .filter(_.id === seriesId)
      .delete
  }
  
  def deleteFrameOfReference(frameOfReferenceId: Long)(implicit session: Session): Int = {
    frameOfReferencesQuery
      .filter(_.id === frameOfReferenceId)
      .delete
  }
  
  def deleteEquipment(equipmentId: Long)(implicit session: Session): Int = {
    equipmentsQuery
      .filter(_.id === equipmentId)
      .delete
  }
  
  def deleteImage(imageId: Long)(implicit session: Session): Int = {
    imagesQuery
      .filter(_.id === imageId)
      .delete
  }
  
  def deleteImageFile(imageId: Long)(implicit session: Session): Int = {
    imageFilesQuery
      .filter(_.id === imageId)
      .delete
  }

}