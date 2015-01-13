package se.vgregion.dicom

import scala.slick.driver.JdbcProfile
import DicomDispatchProtocol.FileName
import DicomDispatchProtocol.Owner
import DicomDispatchProtocol.ImageFile
import DicomHierarchy._
import DicomPropertyValue._
import scala.slick.jdbc.meta.MTable

class DicomMetaDataDAO(val driver: JdbcProfile) {
  import driver.simple._

  // *** Patient *** 

  private case class PatientRow(key: Long, patientName: PatientName, patientID: PatientID, patientBirthDate: PatientBirthDate, patientSex: PatientSex)

  private val toPatientRow = (key: Long, patientName: String, patientID: String, patientBirthDate: String, patientSex: String) =>
    PatientRow(key, PatientName(patientName), PatientID(patientID), PatientBirthDate(patientBirthDate), PatientSex(patientSex))

  private val fromPatientRow = (d: PatientRow) => Option((d.key, d.patientName.value, d.patientID.value, d.patientBirthDate.value, d.patientSex.value))

  private class Patients(tag: Tag) extends Table[PatientRow](tag, "Patients") {
    def key = column[Long]("key", O.PrimaryKey, O.AutoInc)
    def patientName = column[String](DicomProperty.PatientName.name)
    def patientID = column[String](DicomProperty.PatientID.name)
    def patientBirthDate = column[String](DicomProperty.PatientBirthDate.name)
    def patientSex = column[String](DicomProperty.PatientSex.name)
    def * = (key, patientName, patientID, patientBirthDate, patientSex) <> (toPatientRow.tupled, fromPatientRow)
  }

  private val patients = TableQuery[Patients]

  // *** Study *** //

  private case class StudyRow(key: Long, patientKey: Long, studyInstanceUID: StudyInstanceUID, studyDescription: StudyDescription, studyDate: StudyDate, studyID: StudyID, accessionNumber: AccessionNumber)

  private val toStudyRow = (key: Long, patientKey: Long, studyInstanceUID: String, studyDescription: String, studyDate: String, studyID: String, accessionNumber: String) =>
    StudyRow(key, patientKey, StudyInstanceUID(studyInstanceUID), StudyDescription(studyDescription), StudyDate(studyDate), StudyID(studyID), AccessionNumber(accessionNumber))

  private val fromStudyRow = (d: StudyRow) => Option((d.key, d.patientKey, d.studyInstanceUID.value, d.studyDescription.value, d.studyDate.value, d.studyID.value, d.accessionNumber.value))

  private class Studies(tag: Tag) extends Table[StudyRow](tag, "Studies") {
    def key = column[Long]("key", O.PrimaryKey, O.AutoInc)
    def patientKey = column[Long]("patientKey")
    def studyInstanceUID = column[String](DicomProperty.StudyInstanceUID.name)
    def studyDescription = column[String](DicomProperty.StudyDescription.name)
    def studyDate = column[String](DicomProperty.StudyDate.name)
    def studyID = column[String](DicomProperty.StudyID.name)
    def accessionNumber = column[String](DicomProperty.AccessionNumber.name)
    def * = (key, patientKey, studyInstanceUID, studyDescription, studyDate, studyID, accessionNumber) <> (toStudyRow.tupled, fromStudyRow)

    def patientFKey = foreignKey("patientFKey", patientKey, patients)(_.key, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def patientKeyJoin = patients.filter(_.key === patientKey)
  }

  private val studies = TableQuery[Studies]

  // *** Series ***

  private case class SeriesRow(key: Long, studyKey: Long, seriesInstanceUID: SeriesInstanceUID, seriesDescription: SeriesDescription, seriesDate: SeriesDate, modality: Modality, protocolName: ProtocolName, bodyPartExamined: BodyPartExamined)

  private val toSeriesRow = (key: Long, studyKey: Long, seriesInstanceUID: String, seriesDescription: String, seriesDate: String, modality: String, protocolName: String, bodyPartExamined: String) =>
    SeriesRow(key, studyKey, SeriesInstanceUID(seriesInstanceUID), SeriesDescription(seriesDescription), SeriesDate(seriesDate), Modality(modality), ProtocolName(protocolName), BodyPartExamined(bodyPartExamined))

  private val fromSeriesRow = (d: SeriesRow) => Option((d.key, d.studyKey, d.seriesInstanceUID.value, d.seriesDescription.value, d.seriesDate.value, d.modality.value, d.protocolName.value, d.bodyPartExamined.value))

  private class SeriesTable(tag: Tag) extends Table[SeriesRow](tag, "Series") {
    def key = column[Long]("key", O.PrimaryKey, O.AutoInc)
    def studyKey = column[Long]("studyKey")
    def seriesInstanceUID = column[String](DicomProperty.SeriesInstanceUID.name)
    def seriesDescription = column[String](DicomProperty.SeriesDescription.name)
    def seriesDate = column[String](DicomProperty.SeriesDate.name)
    def modality = column[String](DicomProperty.Modality.name)
    def protocolName = column[String](DicomProperty.ProtocolName.name)
    def bodyPartExamined = column[String](DicomProperty.BodyPartExamined.name)
    def * = (key, studyKey, seriesInstanceUID, seriesDescription, seriesDate, modality, protocolName, bodyPartExamined) <> (toSeriesRow.tupled, fromSeriesRow)

    def studyFKey = foreignKey("studyFKey", studyKey, studies)(_.key, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def studyKeyJoin = studies.filter(_.key === studyKey)
  }

  private val seriez = TableQuery[SeriesTable]

  // *** Equipment ***

  private case class EquipmentRow(key: Long, seriesKey: Long, manufacturer: Manufacturer, stationName: StationName)

  private val toEquipmentRow = (key: Long, seriesKey: Long, manufacturer: String, stationName: String) =>
    EquipmentRow(key, seriesKey, Manufacturer(manufacturer), StationName(stationName))

  private val fromEquipmentRow = (d: EquipmentRow) => Option((d.key, d.seriesKey, d.manufacturer.value, d.stationName.value))

  private class Equipments(tag: Tag) extends Table[EquipmentRow](tag, "Equipments") {
    def key = column[Long]("key", O.PrimaryKey, O.AutoInc)
    def seriesKey = column[Long]("seriesKey")
    def manufacturer = column[String](DicomProperty.Manufacturer.name)
    def stationName = column[String](DicomProperty.StationName.name)
    def * = (key, seriesKey, manufacturer, stationName) <> (toEquipmentRow.tupled, fromEquipmentRow)

    def seriesFKey = foreignKey("equipmentSeriesFKey", seriesKey, seriez)(_.key, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def seriesKeyJoin = seriez.filter(_.key === seriesKey)
  }

  private val equipments = TableQuery[Equipments]

  // *** Frame of Reference ***

  private case class FrameOfReferenceRow(key: Long, seriesKey: Long, frameOfReferenceUID: FrameOfReferenceUID)

  private val toFrameOfReferenceRow = (key: Long, seriesKey: Long, frameOfReferenceUID: String) =>
    FrameOfReferenceRow(key, seriesKey, FrameOfReferenceUID(frameOfReferenceUID))

  private val fromFrameOfReferenceRow = (d: FrameOfReferenceRow) => Option((d.key, d.seriesKey, d.frameOfReferenceUID.value))

  private class FrameOfReferences(tag: Tag) extends Table[FrameOfReferenceRow](tag, "FrameOfReferences") {
    def key = column[Long]("key", O.PrimaryKey, O.AutoInc)
    def seriesKey = column[Long]("seriesKey")
    def frameOfReferenceUID = column[String](DicomProperty.FrameOfReferenceUID.name)
    def * = (key, seriesKey, frameOfReferenceUID) <> (toFrameOfReferenceRow.tupled, fromFrameOfReferenceRow)

    def seriesFKey = foreignKey("frameOfReferenceSeriesFKey", seriesKey, seriez)(_.key, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def seriesKeyJoin = seriez.filter(_.key === seriesKey)
  }

  private val frameOfReferences = TableQuery[FrameOfReferences]

  // *** Image ***

  private case class ImageRow(key: Long, seriesKey: Long, sopInstanceUID: SOPInstanceUID, imageType: ImageType)

  private val toImageRow = (key: Long, seriesKey: Long, sopInstanceUID: String, imageType: String) =>
    ImageRow(key, seriesKey, SOPInstanceUID(sopInstanceUID), ImageType(imageType))

  private val fromImageRow = (d: ImageRow) => Option((d.key, d.seriesKey, d.sopInstanceUID.value, d.imageType.value))

  private class Images(tag: Tag) extends Table[ImageRow](tag, "Images") {
    def key = column[Long]("key", O.PrimaryKey, O.AutoInc)
    def seriesKey = column[Long]("seriesKey")
    def sopInstanceUID = column[String](DicomProperty.SOPInstanceUID.name)
    def imageType = column[String](DicomProperty.ImageType.name)
    def * = (key, seriesKey, sopInstanceUID, imageType) <> (toImageRow.tupled, fromImageRow)

    def seriesFKey = foreignKey("seriesFKey", seriesKey, seriez)(_.key, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def seriesKeyJoin = seriez.filter(_.key === seriesKey)
  }

  private val images = TableQuery[Images]

  // *** Files ***

  private case class ImageFileRow(key: Long, imageKey: Long, fileName: FileName, owner: Owner)

  private val toImageFileRow = (key: Long, imageKey: Long, fileName: String, owner: String) => ImageFileRow(key, imageKey, FileName(fileName), Owner(owner))

  private val fromImageFileRow = (d: ImageFileRow) => Option((d.key, d.imageKey, d.fileName.value, d.owner.value))

  private class ImageFiles(tag: Tag) extends Table[ImageFileRow](tag, "ImageFiles") {
    def key = column[Long]("key", O.PrimaryKey, O.AutoInc)
    def imageKey = column[Long]("imageKey")
    def fileName = column[String]("fileName")
    def owner = column[String]("owner")
    def * = (key, imageKey, fileName, owner) <> (toImageFileRow.tupled, fromImageFileRow)

    def imageFKey = foreignKey("imageFKey", imageKey, images)(_.key, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def userKeyJoin = images.filter(_.key === imageKey)
  }

  private val imageFiles = TableQuery[ImageFiles]

  def create(implicit session: Session) =
    if (MTable.getTables("Patients").list.isEmpty)
      (patients.ddl ++
        studies.ddl ++
        equipments.ddl ++
        frameOfReferences.ddl ++
        seriez.ddl ++
        images.ddl ++
        imageFiles.ddl).create

  // *** Db row to object conversions ***

  private def rowToPatient(row: PatientRow) = Patient(row.patientName, row.patientID, row.patientBirthDate, row.patientSex)
  private def rowToStudy(patient: Patient, row: StudyRow) = Study(patient, row.studyInstanceUID, row.studyDescription, row.studyDate, row.studyID, row.accessionNumber)
  private def rowToEquipment(row: EquipmentRow) = Equipment(row.manufacturer, row.stationName)
  private def rowToFrameOfReference(row: FrameOfReferenceRow) = FrameOfReference(row.frameOfReferenceUID)
  private def rowToSeries(study: Study, equipment: Equipment, frameOfReference: FrameOfReference, row: SeriesRow) = Series(study, equipment, frameOfReference, row.seriesInstanceUID, row.seriesDescription, row.seriesDate, row.modality, row.protocolName, row.bodyPartExamined)
  private def rowToImage(series: Series, row: ImageRow) = Image(series, row.sopInstanceUID, row.imageType)
  private def rowToImageFile(image: Image, row: ImageFileRow) = ImageFile(image, row.fileName, row.owner)

  // *** Object to key and key for object

  private def patientForKey(key: Long)(implicit session: Session): Option[Patient] =
    patients.filter(_.key === key).list.map(rowToPatient(_)).headOption

  private def keyForPatient(patient: Patient)(implicit session: Session): Option[Long] =
    patients
      .filter(_.patientName === patient.patientName.value)
      .filter(_.patientID === patient.patientID.value)
      .list.map(_.key).headOption

  private def studyForKey(key: Long)(implicit session: Session): Option[Study] = session.withTransaction {
    studies.filter(_.key === key).list.flatMap(row =>
      patientForKey(row.patientKey).map(rowToStudy(_, row))).headOption
  }

  private def keyForStudy(study: Study)(implicit session: Session): Option[Long] = session.withTransaction {
    keyForPatient(study.patient).flatMap(patientKey =>
      studies
        .filter(_.patientKey === patientKey)
        .filter(_.studyInstanceUID === study.studyInstanceUID.value)
        .list.map(_.key).headOption)
  }

  private def equipmentForSeriesKey(seriesKey: Long)(implicit session: Session): Option[Equipment] =
    equipments.filter(_.seriesKey === seriesKey).list.map(rowToEquipment(_)).headOption

  private def frameOfReferenceForSeriesKey(seriesKey: Long)(implicit session: Session): Option[FrameOfReference] =
    frameOfReferences.filter(_.seriesKey === seriesKey).list.map(rowToFrameOfReference(_)).headOption

  private def seriesForKey(key: Long)(implicit session: Session): Option[Series] = session.withTransaction {
    seriez.filter(_.key === key).list.flatMap(row =>
      studyForKey(row.studyKey).flatMap(study =>
        equipmentForSeriesKey(key).flatMap(equipment =>
          frameOfReferenceForSeriesKey(key).map(frameOfReference =>
            rowToSeries(study, equipment, frameOfReference, row))))).headOption
  }

  private def keyForSeries(series: Series)(implicit session: Session): Option[Long] = session.withTransaction {
    keyForStudy(series.study).flatMap(studyKey =>
      seriez
        .filter(_.studyKey === studyKey)
        .filter(_.seriesInstanceUID === series.seriesInstanceUID.value)
        .list.map(_.key).headOption)
  }

  private def imageForKey(key: Long)(implicit session: Session): Option[Image] = session.withTransaction {
    images.filter(_.key === key).list.flatMap(row =>
      seriesForKey(row.seriesKey).map(rowToImage(_, row))).headOption
  }

  private def keyForImage(image: Image)(implicit session: Session): Option[Long] = session.withTransaction {
    keyForSeries(image.series).flatMap(seriesKey =>
      images
        .filter(_.seriesKey === seriesKey)
        .filter(_.sopInstanceUID === image.sopInstanceUID.value)
        .list.map(_.key).headOption)
  }

  private def imageFileForKey(key: Long)(implicit session: Session): Option[ImageFile] = session.withTransaction {
    imageFiles.filter(_.key === key).list.flatMap(row =>
      imageForKey(row.imageKey).map(rowToImageFile(_, row))).headOption
  }

  private def keyForImageFile(imageFile: ImageFile)(implicit session: Session): Option[Long] = session.withTransaction {
    keyForImage(imageFile.image).flatMap(imageKey =>
      imageFiles
        .filter(_.imageKey === imageKey)
        .list.map(_.key).headOption)
  }

  // *** Inserts ***

  private def insert(patient: Patient)(implicit session: Session): Long = session.withTransaction {
    keyForPatient(patient)
      .getOrElse(
        (patients returning patients.map(_.key)) +=
          PatientRow(-1, patient.patientName, patient.patientID, patient.patientBirthDate, patient.patientSex))
  }

  private def insert(study: Study)(implicit session: Session): Long = session.withTransaction {
    keyForStudy(study)
      .getOrElse {
        val patientKey = insert(study.patient)
        (studies returning studies.map(_.key)) +=
          StudyRow(-1, patientKey, study.studyInstanceUID, study.studyDescription, study.studyDate, study.studyID, study.accessionNumber)
      }
  }

  private def insert(equipment: Equipment, seriesKey: Long)(implicit session: Session): Int =
    equipments += EquipmentRow(-1, seriesKey, equipment.manufacturer, equipment.stationName)

  private def insert(frameOfReference: FrameOfReference, seriesKey: Long)(implicit session: Session): Int =
    frameOfReferences += FrameOfReferenceRow(-1, seriesKey, frameOfReference.frameOfReferenceUID)

  private def insert(series: Series)(implicit session: Session): Long = session.withTransaction {
    keyForSeries(series)
      .getOrElse {
        val studyKey = insert(series.study)
        val seriesKey = (seriez returning seriez.map(_.key)) +=
          SeriesRow(-1, studyKey, series.seriesInstanceUID, series.seriesDescription, series.seriesDate, series.modality, series.protocolName, series.bodyPartExamined)
        insert(series.equipment, seriesKey)
        insert(series.frameOfReference, seriesKey)
        seriesKey
      }
  }

  private def insert(image: Image)(implicit session: Session): Long = session.withTransaction {
    keyForImage(image)
      .getOrElse {
        val seriesKey = insert(image.series)
        (images returning images.map(_.key)) +=
          ImageRow(-1, seriesKey, image.sopInstanceUID, image.imageType)
      }
  }

  def insert(imageFile: ImageFile)(implicit session: Session): Long = session.withTransaction {
    keyForImageFile(imageFile)
      .getOrElse {
        val imageKey = insert(imageFile.image)
        (imageFiles returning imageFiles.map(_.key)) +=
          ImageFileRow(-1, imageKey, imageFile.fileName, imageFile.owner)
      }
  }

  // *** Listing all patients, studies etc ***

  def allPatients(implicit session: Session): List[Patient] = patients.list.map(rowToPatient(_))

  def allStudies(implicit session: Session): List[Study] = session.withTransaction {
    studies.list.map(row =>
      patientForKey(row.patientKey).map(rowToStudy(_, row))).flatten
  }

  def allEquipments(implicit session: Session): List[Equipment] = equipments.list.map(rowToEquipment(_))

  def allFrameOfReferences(implicit session: Session): List[FrameOfReference] = frameOfReferences.list.map(rowToFrameOfReference(_))

  def allSeries(implicit session: Session): List[Series] = session.withTransaction {
    seriez.list.map(row =>
      studyForKey(row.studyKey).flatMap(study =>
        equipmentForSeriesKey(row.key).flatMap(equipment =>
          frameOfReferenceForSeriesKey(row.key).map(frameOfReference =>
            rowToSeries(study, equipment, frameOfReference, row))))).flatten
  }

  def allImages(implicit session: Session): List[Image] = session.withTransaction {
    images.list.map(row =>
      seriesForKey(row.seriesKey).map(rowToImage(_, row))).flatten
  }

  def allImageFiles(implicit session: Session): List[ImageFile] = session.withTransaction {
    imageFiles.list.map(row =>
      imageForKey(row.imageKey).map(rowToImageFile(_, row))).flatten
  }

  // *** Per owner listings ***

  def patientsForOwner(owner: Owner)(implicit session: Session): List[Patient] = session.withTransaction {
    (for {
      ps <- patients
      ss <- studies if ps.key === ss.patientKey
      sz <- seriez if ss.key === sz.studyKey
      is <- images if sz.key === is.seriesKey
      ifs <- imageFiles if is.key === ifs.imageKey
    } yield (ps, ifs))
      .filter(_._2.owner === owner.value)
      .list.map(row =>
        rowToPatient(row._1))
      .toSet.toList
  }

  def studiesForOwner(owner: Owner)(implicit session: Session): List[Study] = session.withTransaction {
    (for {
      ss <- studies
      sz <- seriez if ss.key === sz.studyKey
      is <- images if sz.key === is.seriesKey
      ifs <- imageFiles if is.key === ifs.imageKey
    } yield (ss, ifs))
      .filter(_._2.owner === owner.value)
      .list.map(row =>
        patientForKey(row._1.patientKey).map(rowToStudy(_, row._1))).flatten
      .toSet.toList
  }

  def seriesForOwner(owner: Owner)(implicit session: Session): List[Series] = session.withTransaction {
    (for {
      sz <- seriez
      is <- images if sz.key === is.seriesKey
      ifs <- imageFiles if is.key === ifs.imageKey
    } yield (sz, ifs))
      .filter(_._2.owner === owner.value)
      .list.map(row =>
        studyForKey(row._1.studyKey).flatMap(study =>
          equipmentForSeriesKey(row._1.key).flatMap(equipment =>
            frameOfReferenceForSeriesKey(row._1.key).map(frameOfReference =>
              rowToSeries(study, equipment, frameOfReference, row._1))))).flatten
      .toSet.toList
  }

  def imagesForOwner(owner: Owner)(implicit session: Session): List[Image] = session.withTransaction {
    (for {
      is <- images
      ifs <- imageFiles if is.key === ifs.imageKey
    } yield (is, ifs))
      .filter(_._2.owner === owner.value)
      .list.map(row =>
        seriesForKey(row._1.seriesKey).map(rowToImage(_, row._1))).flatten
      .toSet.toList
  }

  def imageFilesForOwner(owner: Owner)(implicit session: Session): List[ImageFile] = session.withTransaction {
    imageFiles
      .filter(_.owner === owner.value)
      .list.map(row =>
        imageForKey(row.imageKey).map(rowToImageFile(_, row))).flatten
  }

  // *** Grouped listings ***

  def studiesForPatient(patient: Patient)(implicit session: Session): List[Study] = session.withTransaction {
    keyForPatient(patient).map(patientKey =>
      studies
        .filter(_.patientKey === patientKey)
        .list.map(rowToStudy(patient, _)))
      .getOrElse(List())
  }

  def seriesForStudy(study: Study)(implicit session: Session): List[Series] = session.withTransaction {
    keyForStudy(study).map(studyKey =>
      seriez
        .filter(_.studyKey === studyKey)
        .list.map(row =>
          equipmentForSeriesKey(row.key).flatMap(equipment =>
            frameOfReferenceForSeriesKey(row.key).map(frameOfReference =>
              rowToSeries(study, equipment, frameOfReference, row)))).flatten)
      .getOrElse(List())
  }

  def imagesForSeries(series: Series)(implicit session: Session): List[Image] = session.withTransaction {
    keyForSeries(series).map(seriesKey =>
      images
        .filter(_.seriesKey === seriesKey)
        .list.map(rowToImage(series, _)))
      .getOrElse(List())
  }

  def imageFilesForImage(image: Image)(implicit session: Session): List[ImageFile] = session.withTransaction {
    keyForImage(image).map(imageKey =>
      imageFiles
        .filter(_.imageKey === imageKey)
        .list.map(rowToImageFile(image, _)))
      .getOrElse(List())
  }

  // *** Grouped and per owner listings ***

  def studiesForPatient(patient: Patient, owner: Owner)(implicit session: Session): List[Study] = session.withTransaction {
    keyForPatient(patient).map(patientKey =>
      (for {
        ss <- studies
        sz <- seriez if ss.key === sz.studyKey
        is <- images if sz.key === is.seriesKey
        ifs <- imageFiles if is.key === ifs.imageKey
      } yield (ss, ifs))
        .filter(_._2.owner === owner.value)
        .filter(_._1.patientKey === patientKey)
        .list.map(row =>
          rowToStudy(patient, row._1))
        .toSet.toList)
      .getOrElse(List())
  }

  def seriesForStudy(study: Study, owner: Owner)(implicit session: Session): List[Series] = session.withTransaction {
    keyForStudy(study).map(studyKey =>
      (for {
        sz <- seriez
        is <- images if sz.key === is.seriesKey
        ifs <- imageFiles if is.key === ifs.imageKey
      } yield (sz, ifs))
        .filter(_._2.owner === owner.value)
        .filter(_._1.studyKey === studyKey)
        .list.map(row =>
          equipmentForSeriesKey(row._1.key).flatMap(equipment =>
            frameOfReferenceForSeriesKey(row._1.key).map(frameOfReference =>
              rowToSeries(study, equipment, frameOfReference, row._1)))).flatten
        .toSet.toList)
      .getOrElse(List())
  }

  def imagesForSeries(series: Series, owner: Owner)(implicit session: Session): List[Image] = session.withTransaction {
    keyForSeries(series).map(seriesKey =>
      (for {
        is <- images
        ifs <- imageFiles if is.key === ifs.imageKey
      } yield (is, ifs))
        .filter(_._2.owner === owner.value)
        .filter(_._1.seriesKey === seriesKey)
        .list.map(row =>
          rowToImage(series, row._1))
        .toSet.toList)
      .getOrElse(List())
  }

  def imageFilesForImage(image: Image, owner: Owner)(implicit session: Session): List[ImageFile] = session.withTransaction {
    keyForImage(image).map(imageKey =>
      imageFiles
        .filter(_.imageKey === imageKey)
        .filter(_.owner === owner.value)
        .list.map(rowToImageFile(image, _)))
      .getOrElse(List())
  }

  // *** Owner change ***

  def changeOwner(imageFile: ImageFile, newOwner: Owner)(implicit session: Session): Int = session.withTransaction {
    keyForImageFile(imageFile).map(imageFileKey =>
      imageFiles
        .filter(_.key === imageFileKey)
        .map(_.owner)
        .update(newOwner.value))
      .getOrElse(0)
  }

  // *** Deletes ***

  def deletePatient(patient: Patient)(implicit session: Session): Option[Int] = session.withTransaction {
    keyForPatient(patient).map(patientKey => patients.filter(_.key === patientKey).delete)
  }

  def deleteStudy(study: Study)(implicit session: Session): Option[Int] = session.withTransaction {
    val result = keyForStudy(study).map(studyKey => studies.filter(_.key === studyKey).delete)
    if (studiesForPatient(study.patient).isEmpty)
      deletePatient(study.patient)
    result
  }

  def deleteSeries(series: Series)(implicit session: Session): Option[Int] = session.withTransaction {
    val result = keyForSeries(series).map(seriesKey => seriez.filter(_.key === seriesKey).delete)
    if (seriesForStudy(series.study).isEmpty)
      deleteStudy(series.study)
    result
  }

  def deleteImage(image: Image)(implicit session: Session): Option[Int] = session.withTransaction {
    val result = keyForImage(image).map(imageKey => images.filter(_.key === imageKey).delete)
    if (imagesForSeries(image.series).isEmpty)
      deleteSeries(image.series)
    result
  }

  def deleteImageFile(imageFile: ImageFile)(implicit session: Session): Option[Int] = session.withTransaction {
    val result = keyForImageFile(imageFile).map(imageFileKey => imageFiles.filter(_.key === imageFileKey).delete)
    if (imageFilesForImage(imageFile.image).isEmpty)
      deleteImage(imageFile.image)
    result
  }

  // *** Deletes per owner ***

  def deletePatient(patient: Patient, owner: Owner)(implicit session: Session): Unit = session.withTransaction {
    studiesForPatient(patient, owner) foreach (
      deleteStudy(_, owner))
  }

  def deleteStudy(study: Study, owner: Owner)(implicit session: Session): Unit = session.withTransaction {
    seriesForStudy(study, owner) foreach (
      deleteSeries(_, owner))
  }

  def deleteSeries(series: Series, owner: Owner)(implicit session: Session): Unit = session.withTransaction {
    imagesForSeries(series, owner) foreach (
      deleteImage(_, owner))
  }

  def deleteImage(image: Image, owner: Owner)(implicit session: Session): Unit = session.withTransaction {
    imageFilesForImage(image, owner) foreach (
      deleteImageFile(_, owner))
  }

  def deleteImageFile(imageFile: ImageFile, owner: Owner)(implicit session: Session): Unit = session.withTransaction {
    keyForImageFile(imageFile).map(imageFileKey =>
      imageFiles
        .filter(_.key === imageFileKey)
        .filter(_.owner === owner.value)
        .delete)
    if (imageFilesForImage(imageFile.image).isEmpty)
      deleteImage(imageFile.image)
  }

  // *** Counts (for testing) ***

  def patientCount(implicit session: Session): Int = patients.length.run
  def studyCount(implicit session: Session): Int = studies.length.run
  def seriesCount(implicit session: Session): Int = seriez.length.run
  def imageCount(implicit session: Session): Int = images.length.run
  def imageFileCount(implicit session: Session): Int = imageFiles.length.run
  def equipmentCount(implicit session: Session): Int = equipments.length.run
  def frameOfReferenceCount(implicit session: Session): Int = frameOfReferences.length.run

}