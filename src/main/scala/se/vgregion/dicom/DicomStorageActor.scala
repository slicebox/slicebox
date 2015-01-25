package se.vgregion.dicom

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import akka.actor.Actor
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.event.LoggingReceive

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag

import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomProtocol.AddDataset
import se.vgregion.dicom.DicomProtocol.DeleteImage
import se.vgregion.dicom.DicomProtocol.DeletePatient
import se.vgregion.dicom.DicomProtocol.DeleteSeries
import se.vgregion.dicom.DicomProtocol.DeleteStudy
import se.vgregion.dicom.DicomProtocol.FileName
import se.vgregion.dicom.DicomProtocol.GetAllImageFiles
import se.vgregion.dicom.DicomProtocol.GetAllImages
import se.vgregion.dicom.DicomProtocol.GetImageFiles
import se.vgregion.dicom.DicomProtocol.GetImages
import se.vgregion.dicom.DicomProtocol.GetPatients
import se.vgregion.dicom.DicomProtocol.GetSeries
import se.vgregion.dicom.DicomProtocol.GetStudies
import se.vgregion.dicom.DicomProtocol.ImageAdded
import se.vgregion.dicom.DicomProtocol.ImageFile
import se.vgregion.dicom.DicomProtocol.ImageFiles
import se.vgregion.dicom.DicomProtocol.ImageFilesDeleted
import se.vgregion.dicom.DicomProtocol.Images
import se.vgregion.dicom.DicomProtocol.MetaDataQuery
import se.vgregion.dicom.DicomProtocol.MetaDataUpdate
import se.vgregion.dicom.DicomProtocol.Patients
import se.vgregion.dicom.DicomProtocol.SeriesCollection
import se.vgregion.dicom.DicomProtocol.StoreDataset
import se.vgregion.dicom.DicomProtocol.StoreFile
import se.vgregion.dicom.DicomProtocol.Studies

import DicomHierarchy.Equipment
import DicomHierarchy.FrameOfReference
import DicomHierarchy.Image
import DicomHierarchy.Patient
import DicomHierarchy.Series
import DicomHierarchy.Study
import DicomPropertyValue.AccessionNumber
import DicomPropertyValue.BodyPartExamined
import DicomPropertyValue.FrameOfReferenceUID
import DicomPropertyValue.ImageType
import DicomPropertyValue.Manufacturer
import DicomPropertyValue.Modality
import DicomPropertyValue.PatientBirthDate
import DicomPropertyValue.PatientID
import DicomPropertyValue.PatientName
import DicomPropertyValue.PatientSex
import DicomPropertyValue.ProtocolName
import DicomPropertyValue.SOPInstanceUID
import DicomPropertyValue.SeriesDate
import DicomPropertyValue.SeriesDescription
import DicomPropertyValue.SeriesInstanceUID
import DicomPropertyValue.StationName
import DicomPropertyValue.StudyDate
import DicomPropertyValue.StudyDescription
import DicomPropertyValue.StudyID
import DicomPropertyValue.StudyInstanceUID
import DicomUtil._

class DicomStorageActor(dbProps: DbProps, storage: Path) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DicomMetaDataDAO(dbProps.driver)

  setupDb()

  def receive = LoggingReceive {

    case StoreFile(path) =>
      val dataset = loadDataset(path, true)
      if (checkSopClass(dataset)) {
        storeDataset(dataset)
        log.info("Stored file: " + path)
      } else {
        log.info(s"Received file with unsupported SOP Class UID ${dataset.getString(Tag.SOPClassUID)}, skipping")
      }

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
    val anonymizedDataset = DicomAnonymization.anonymizeDataset(dataset)
    saveDataset(anonymizedDataset, storedPath)
    db.withSession { implicit session =>
      dao.insert(imageFile)
    }
    imageFile
  }

  def fileName(dataset: Attributes): String = dataset.getString(Tag.SOPInstanceUID)

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

}

object DicomStorageActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new DicomStorageActor(dbProps, storage))
}
