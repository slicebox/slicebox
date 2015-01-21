package se.vgregion.dicom

import java.io.BufferedInputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.io.DicomInputStream.IncludeBulkData
import org.dcm4che3.io.DicomOutputStream
import org.dcm4che3.util.SafeClose
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import DicomHierarchy._
import DicomPropertyValue._
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.app.DbProps
import java.nio.file.Paths
import org.dcm4che3.data.VR
import org.dcm4che3.util.TagUtils
import org.dcm4che3.data.Attributes.Visitor
import java.util.Date

class DicomStorageActor(dbProps: DbProps, storage: Path) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DicomMetaDataDAO(dbProps.driver)

  val transferSyntax = UID.ExplicitVRLittleEndian

  setupDb()

  def receive = LoggingReceive {

    case StoreFile(path) =>
      val dataset = loadDicom(path, true)
      storeDataset(dataset)
      log.info("Stored file: " + path)

    case StoreDataset(dataset) =>
      storeDataset(dataset)

    case AddDataset(dataset) =>
      val imageFile = storeDataset(dataset)
      sender ! ImageAdded(imageFile.image)

    case msg: MetaDataUpdate => msg match {

      case DeleteImage(image) =>
        db.withSession { implicit session =>
          val files = dao.imageFilesForImage(image)
          dao.deleteImage(image)
          deleteFromStorage(files)
          sender ! ImageFilesDeleted(files)
        }

      case DeleteSeries(series) =>
        db.withSession { implicit session =>
          val files = dao.imageFilesForSeries(series)
          dao.deleteSeries(series)
          deleteFromStorage(files)
          sender ! ImageFilesDeleted(files)
        }

      case DeleteStudy(study) =>
        db.withSession { implicit session =>
          val files = dao.imageFilesForStudy(study)
          dao.deleteStudy(study)
          deleteFromStorage(files)
          sender ! ImageFilesDeleted(files)
        }

      case DeletePatient(patient) =>
        db.withSession { implicit session =>
          val files = dao.imageFilesForPatient(patient)
          dao.deletePatient(patient)
          deleteFromStorage(files)
          sender ! ImageFilesDeleted(files)
        }
    }

    case GetAllImageFiles =>
      db.withSession { implicit session =>
        sender ! ImageFiles(dao.allImageFiles)
      }

    case GetImageFiles(image) =>
      db.withSession { implicit session =>
        sender ! ImageFiles(dao.imageFilesForImage(image))
      }

    case msg: MetaDataQuery => msg match {
      case GetAllImages =>
        db.withSession { implicit session =>
          sender ! Images(dao.allImages)
        }

      case GetPatients =>
        db.withSession { implicit session =>
          sender ! Patients(dao.allPatients)
        }
      case GetStudies(patient) =>
        db.withSession { implicit session =>
          sender ! Studies(dao.studiesForPatient(patient))
        }
      case GetSeries(study) =>
        db.withSession { implicit session =>
          sender ! SeriesCollection(dao.seriesForStudy(study))
        }
      case GetImages(series) =>
        db.withSession { implicit session =>
          sender ! Images(dao.imagesForSeries(series))
        }

    }

  }

  def storeDataset(dataset: Attributes): ImageFile = {
    val name = fileName(dataset)
    val storedPath = storage.resolve(name)
    val image = datasetToImage(dataset)
    val imageFile = ImageFile(image, FileName(name))
    writeToStorage(anonymizeDicom(dataset), storedPath)
    db.withSession { implicit session =>
      dao.insert(imageFile)
    }
    imageFile
  }

  def loadDicom(path: Path, withPixelData: Boolean): Attributes = {
    val dis = new DicomInputStream(new BufferedInputStream(Files.newInputStream(path)))
    val dataset =
      if (withPixelData)
        dis.readDataset(-1, -1)
      else {
        dis.setIncludeBulkData(IncludeBulkData.NO)
        dis.readDataset(-1, Tag.PixelData);
      }
    dataset
  }

  def cloneDataset(dataset: Attributes): Attributes = new Attributes(dataset)

  def anonymizeDicom(dataset: Attributes): Attributes = {

    val emptyString = ""
    val anonymousString = "anonymous"
    val unknownString = "unknown"
    val anonymousDate = new Date(0)
    val anonymousPregnancyStatus = 4

    val uuid = UUID.randomUUID().toString()

    val patientName = dataset.getString(Tag.PatientName)
    val patientId = dataset.getString(Tag.PatientID)
    val sex = dataset.getString(Tag.PatientSex)
    val age = dataset.getString(Tag.PatientAge)

    val modified = cloneDataset(dataset)

    // replace tags
    setStringTag(modified, Tag.InstitutionName, VR.LO, emptyString)
    setStringTag(modified, Tag.ReferringPhysicianName, VR.PN, emptyString)
    setStringTag(modified, Tag.StationName, VR.SH, anonymousString)
    setStringTag(modified, Tag.OperatorsName, VR.PN, anonymousString)
    setStringTag(modified, Tag.IssuerOfPatientID, VR.LO, anonymousString)
    setStringTag(modified, Tag.PatientBirthName, VR.PN, anonymousString)
    setStringTag(modified, Tag.PatientMotherBirthName, VR.PN, anonymousString)
    setStringTag(modified, Tag.MedicalAlerts, VR.LO, anonymousString)
    setStringTag(modified, 0x00102110, VR.LO, anonymousString) // contrast allergies
    setStringTag(modified, Tag.CountryOfResidence, VR.LO, anonymousString)
    setStringTag(modified, Tag.RegionOfResidence, VR.LO, anonymousString)
    setStringTag(modified, Tag.PatientTelephoneNumbers, VR.SH, anonymousString)
    setStringTag(modified, Tag.SmokingStatus, VR.CS, unknownString)
    setStringTag(modified, Tag.PatientReligiousPreference, VR.LO, anonymousString)
    setStringTag(modified, Tag.DeviceSerialNumber, VR.LO, anonymousString)
    setStringTag(modified, Tag.ProtocolName, VR.LO, anonymousString)
    setStringTag(modified, Tag.AccessionNumber, VR.SH, s"R$uuid")
    setStringTag(modified, Tag.PatientName, VR.SH, s"$anonymousString $sex $age")
    setStringTag(modified, Tag.PatientID, VR.LO, s"anon$uuid")
    setStringTag(modified, Tag.StudyID, VR.SH, s"E$uuid")
    setStringTag(modified, Tag.PerformedProcedureStepID, VR.SH, s"E$uuid")
    setStringTag(modified, Tag.RequestedProcedureID, VR.SH, s"E$uuid")
    setDateTag(modified, Tag.PatientBirthDate, VR.DA, anonymousDate)
    setIntTag(modified, Tag.PregnancyStatus, VR.US, anonymousPregnancyStatus)
    // TODO generate UID tags from dicom root
    // setStringTag(modified, Tag.StudyInstanceUID, VR.UI, "")
    // setStringTag(modified, Tag.SeriesInstanceUID, VR.UI, "")
    // setStringTag(modified, Tag.SOPInstanceUID, VR.UI, "")

    // remove tags
    removeTag(modified, Tag.InstitutionAddress)
    removeTag(modified, Tag.ReferringPhysicianAddress)
    removeTag(modified, Tag.ReferringPhysicianTelephoneNumbers)
    removeTag(modified, Tag.InstitutionalDepartmentName)
    removeTag(modified, Tag.PhysiciansOfRecord)
    removeTag(modified, Tag.PerformingPhysicianName)
    removeTag(modified, Tag.NameOfPhysiciansReadingStudy)
    removeTag(modified, Tag.AdmittingDiagnosesDescription)
    removeTag(modified, Tag.DerivationDescription)
    removeTag(modified, Tag.SourceImageSequence)
    removeTag(modified, Tag.PatientBirthTime)
    removeTag(modified, Tag.PatientInsurancePlanCodeSequence)
    removeTag(modified, Tag.OtherPatientIDs)
    removeTag(modified, Tag.OtherPatientNames)
    removeTag(modified, Tag.MilitaryRank)
    removeTag(modified, Tag.BranchOfService)
    removeTag(modified, Tag.MedicalRecordLocator)
    removeTag(modified, Tag.EthnicGroup)
    removeTag(modified, Tag.Occupation)
    removeTag(modified, Tag.AdditionalPatientHistory)
    removeTag(modified, Tag.LastMenstrualDate)
    removeTag(modified, Tag.PatientComments)
    removeTag(modified, Tag.ImageComments)
    removeTag(modified, Tag.AdmissionID)
    removeTag(modified, Tag.IssuerOfAdmissionID)
    removeTag(modified, Tag.IssuerOfAdmissionIDSequence)
    removeTag(modified, Tag.PerformedProcedureStepDescription)
    removeTag(modified, Tag.ScheduledStepAttributesSequence)
    removeTag(modified, Tag.RequestAttributesSequence)
    removeTag(modified, Tag.ReferencedRequestSequence)

    // remove private tags for good measure any tags containing either the patient name or id
    var toRemove = Seq.empty[Int]

    modified.accept(new Visitor() {
      def visit(attrs: Attributes, tag: Int, vr: VR, value: Object): Boolean = {

        if (TagUtils.isPrivateTag(tag))
          toRemove = toRemove :+ tag

        if (value.isInstanceOf[String]) {
          val stringValue = value.asInstanceOf[String]
          if (stringValue.contains(patientName) || stringValue.contains(patientId))
            toRemove = toRemove :+ tag
        }

        true
      }
    }, true)

    toRemove.foreach(tag => removeTag(modified, tag))

    modified
  }

  def setStringTag(dataset: Attributes, tag: Int, vr: VR, value: String): Unit = dataset.setString(tag, vr, value)
  def setDateTag(dataset: Attributes, tag: Int, vr: VR, value: Date): Unit = dataset.setDate(tag, vr, value)
  def setIntTag(dataset: Attributes, tag: Int, vr: VR, value: Int): Unit = dataset.setInt(tag, vr, value)
  def removeTag(dataset: Attributes, tag: Int): Unit = dataset.remove(tag)
  def fileName(dataset: Attributes): String = dataset.getString(Tag.SOPInstanceUID)

  def writeToStorage(dataset: Attributes, filePath: Path): Unit = {
    val out = new DicomOutputStream(Files.newOutputStream(filePath), transferSyntax)
    val metaInformation = dataset.createFileMetaInformation(transferSyntax)
    try {
      out.writeDataset(metaInformation, dataset)
    } finally {
      SafeClose.close(out)
    }
  }

  def deleteFromStorage(imageFiles: Seq[ImageFile]): Unit = imageFiles foreach (deleteFromStorage(_))
  def deleteFromStorage(imageFile: ImageFile): Unit = deleteFromStorage(Paths.get(imageFile.fileName.value))
  def deleteFromStorage(filePath: Path): Unit = {
    Files.delete(filePath)
    log.info("Deleted file " + filePath)
  }

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def datasetToImage(dataset: Attributes): Image =
    Image(Series(Study(Patient(

      PatientName(Option(dataset.getString(DicomProperty.PatientName.dicomTag)).getOrElse("")),
      PatientID(Option(dataset.getString(DicomProperty.PatientID.dicomTag)).getOrElse("")),
      PatientBirthDate(Option(dataset.getString(DicomProperty.PatientBirthDate.dicomTag)).getOrElse("")),
      PatientSex(Option(dataset.getString(DicomProperty.PatientSex.dicomTag)).getOrElse(""))),

      StudyInstanceUID(Option(dataset.getString(DicomProperty.StudyInstanceUID.dicomTag)).getOrElse("")),
      StudyDescription(Option(dataset.getString(DicomProperty.StudyDescription.dicomTag)).getOrElse("")),
      StudyDate(Option(dataset.getString(DicomProperty.StudyDate.dicomTag)).getOrElse("")),
      StudyID(Option(dataset.getString(DicomProperty.StudyID.dicomTag)).getOrElse("")),
      AccessionNumber(Option(dataset.getString(DicomProperty.AccessionNumber.dicomTag)).getOrElse(""))),

      Equipment(
        Manufacturer(Option(dataset.getString(DicomProperty.Manufacturer.dicomTag)).getOrElse("")),
        StationName(Option(dataset.getString(DicomProperty.StationName.dicomTag)).getOrElse(""))),
      FrameOfReference(
        FrameOfReferenceUID(Option(dataset.getString(DicomProperty.FrameOfReferenceUID.dicomTag)).getOrElse(""))),
      SeriesInstanceUID(Option(dataset.getString(DicomProperty.SeriesInstanceUID.dicomTag)).getOrElse("")),
      SeriesDescription(Option(dataset.getString(DicomProperty.SeriesDescription.dicomTag)).getOrElse("")),
      SeriesDate(Option(dataset.getString(DicomProperty.SeriesDate.dicomTag)).getOrElse("")),
      Modality(Option(dataset.getString(DicomProperty.Modality.dicomTag)).getOrElse("")),
      ProtocolName(Option(dataset.getString(DicomProperty.ProtocolName.dicomTag)).getOrElse("")),
      BodyPartExamined(Option(dataset.getString(DicomProperty.BodyPartExamined.dicomTag)).getOrElse(""))),

      SOPInstanceUID(Option(dataset.getString(DicomProperty.SOPInstanceUID.dicomTag)).getOrElse("")),
      ImageType(readSequence(dataset.getStrings(DicomProperty.ImageType.dicomTag))))

  def readSequence(sequence: Array[String]): String =
    if (sequence == null || sequence.length == 0)
      ""
    else
      sequence.tail.foldLeft(sequence.head)((result, part) => result + "/" + part)

}

object DicomStorageActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new DicomStorageActor(dbProps, storage))
}
