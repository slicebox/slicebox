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
import se.vgregion.dicom.DicomProtocol._
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
import se.vgregion.dicom.DicomProtocol.DatasetReceived
import se.vgregion.util.ExceptionCatching

class DicomStorageActor(dbProps: DbProps, storage: Path) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DicomMetaDataDAO(dbProps.driver)

  setupDb()

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[DatasetReceived])
  }

  def receive = LoggingReceive {

    case DatasetReceived(dataset) =>
      storeDataset(dataset)
      log.info("Stored dataset: " + dataset.getString(Tag.SOPInstanceUID))

    case AddDataset(dataset) =>
      catchAndReport {
        val image = storeDataset(dataset)
        sender ! ImageAdded(image)
      }

    case msg: MetaDataUpdate =>

      catchAndReport {

        msg match {

          case DeleteImage(imageId) =>
            db.withSession { implicit session =>
              val imageFiles = dao.imageFileForImage(imageId).toList
              dao.deleteImage(imageId)
              deleteFromStorage(imageFiles)
              sender ! ImageFilesDeleted(imageFiles)
            }

          case DeleteSeries(seriesId) =>
            db.withSession { implicit session =>
              val imageFiles = dao.imageFilesForSeries(seriesId)
              dao.deleteSeries(seriesId)
              deleteFromStorage(imageFiles)
              sender ! ImageFilesDeleted(imageFiles)
            }

          case DeleteStudy(studyId) =>
            db.withSession { implicit session =>
              val imageFiles = dao.imageFilesForStudy(studyId)
              dao.deleteStudy(studyId)
              deleteFromStorage(imageFiles)
              sender ! ImageFilesDeleted(imageFiles)
            }

          case DeletePatient(patientId) =>
            db.withSession { implicit session =>
              val imageFiles = dao.imageFilesForPatient(patientId)
              dao.deletePatient(patientId)
              deleteFromStorage(imageFiles)
              sender ! ImageFilesDeleted(imageFiles)
            }

        }
      }

    case GetAllImageFiles =>
      catchAndReport {
        db.withSession { implicit session =>
          sender ! ImageFiles(dao.imageFiles)
        }
      }

    case GetImageFiles(imageId) =>
      catchAndReport {
        db.withSession { implicit session =>
          sender ! ImageFiles(dao.imageFileForImage(imageId).toList)
        }
      }
      
    case GetImageFilesForSeries(seriesId) =>
      catchAndReport {
        db.withSession { implicit session =>
          sender ! ImageFiles(dao.imageFilesForSeries(seriesId).toList)
        }
      }

    case msg: MetaDataQuery => msg match {
      case GetAllImages =>
        catchAndReport {
          db.withSession { implicit session =>
            sender ! Images(dao.images)
          }
        }

      case GetPatients =>
        catchAndReport {
          db.withSession { implicit session =>
            sender ! Patients(dao.patients)
          }
        }

      case GetStudies(patientId) =>
        catchAndReport {
          db.withSession { implicit session =>
            sender ! Studies(dao.studiesForPatient(patientId))
          }
        }

      case GetSeries(studyId) =>
        catchAndReport {
          db.withSession { implicit session =>
            sender ! SeriesCollection(dao.seriesForStudy(studyId))
          }
        }

      case GetImages(seriesId) =>
        catchAndReport {
          db.withSession { implicit session =>
            sender ! Images(dao.imagesForSeries(seriesId))
          }
        }

    }

  }

  def storeDataset(dataset: Attributes): Image = {
    val name = fileName(dataset)
    val storedPath = storage.resolve(name)

    db.withSession { implicit session =>
      val patient = datasetToPatient(dataset)
      val dbPatient = dao.patientByNameAndID(patient)
        .getOrElse(dao.insert(patient))

      val study = datasetToStudy(dataset)
      val dbStudy = dao.studyByUid(study)
        .getOrElse(dao.insert(study.copy(patientId = dbPatient.id)))

      val equipment = datasetToEquipment(dataset)
      val dbEquipment = dao.equipmentByManufacturerAndStationName(equipment)
        .getOrElse(dao.insert(equipment))

      val frameOfReference = datasetToFrameOfReference(dataset)
      val dbFrameOfReference = dao.frameOfReferenceByUid(frameOfReference)
        .getOrElse(dao.insert(frameOfReference))

      val series = datasetToSeries(dataset)
      val dbSeries = dao.seriesByUid(series)
        .getOrElse(dao.insert(series.copy(
          studyId = dbStudy.id,
          equipmentId = dbEquipment.id,
          frameOfReferenceId = dbFrameOfReference.id)))

      val image = datasetToImage(dataset)
      val dbImage = dao.imageByUid(image)
        .getOrElse(dao.insert(image.copy(seriesId = dbSeries.id)))

      val imageFile = ImageFile(dbImage.id, FileName(name))
      val dbImageFile = dao.imageFileByFileName(imageFile)
        .getOrElse(dao.insert(imageFile))

      val anonymizedDataset = DicomAnonymization.anonymizeDataset(dataset)
      saveDataset(anonymizedDataset, storedPath)

      dbImage
    }
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
