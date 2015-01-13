package se.vgregion.dicom

import org.dcm4che3.data.Attributes
import akka.actor.Actor
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.app._
import DicomProtocol._
import DicomHierarchy._
import DicomPropertyValue._
import akka.actor.ActorRef

class DicomMetaDataActor(dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DicomMetaDataDAO(dbProps.driver)

  setupDb()

  def receive = LoggingReceive {

    case GetAllImageFiles(owner) =>
      db.withSession { implicit session =>
        sender ! owner.map(o => ImageFiles(dao.imageFilesForOwner(o))).getOrElse(ImageFiles(dao.allImageFiles))
      }

    case GetImageFiles(image, owner) =>
      db.withSession { implicit session =>
        sender ! owner.map(o => ImageFiles(dao.imageFilesForImage(image, o))).getOrElse(ImageFiles(dao.imageFilesForImage(image)))
      }

    case msg: MetaDataRequest => msg match {
      case DeleteImage(image, owner) =>
        db.withSession { implicit session =>
          owner match {
            case Some(o) => dao.deleteImage(image, o)
            case _       => dao.deleteImage(image)
          }
        }
        sender ! ImageDeleted(image)

      case DeleteSeries(series, owner) =>
        db.withSession { implicit session =>
          owner match {
            case Some(o) => dao.deleteSeries(series, o)
            case _       => dao.deleteSeries(series)
          }
        }
        sender ! SeriesDeleted(series)

      case DeleteStudy(study, owner) =>
        db.withSession { implicit session =>
          owner match {
            case Some(o) => dao.deleteStudy(study, o)
            case _       => dao.deleteStudy(study)
          }
        }
        sender ! StudyDeleted(study)

      case DeletePatient(patient, owner) =>
        db.withSession { implicit session =>
          owner match {
            case Some(o) => dao.deletePatient(patient, o)
            case _       => dao.deletePatient(patient)
          }
        }
        sender ! PatientDeleted(patient)

      case GetAllImages(owner) =>
        db.withSession { implicit session =>
          sender ! owner.map(o => Images(dao.imagesForOwner(o))).getOrElse(Images(dao.allImages))
        }

      case GetPatients(owner) =>
        db.withSession { implicit session =>
          sender ! owner.map(o => Patients(dao.patientsForOwner(o))).getOrElse(Patients(dao.allPatients))
        }
      case GetStudies(patient, owner) =>
        db.withSession { implicit session =>
          sender ! owner.map(o => Studies(dao.studiesForPatient(patient, o))).getOrElse(Studies(dao.studiesForPatient(patient)))
        }
      case GetSeries(study, owner) =>
        db.withSession { implicit session =>
          sender ! owner.map(o => SeriesCollection(dao.seriesForStudy(study, o))).getOrElse(SeriesCollection(dao.seriesForStudy(study)))
        }
      case GetImages(series, owner) =>
        db.withSession { implicit session =>
          sender ! owner.map(o => Images(dao.imagesForSeries(series, o))).getOrElse(Images(dao.imagesForSeries(series)))
        }

      case ChangeOwner(image: Image, previousOwner: Owner, newOwner: Owner) =>
        db.withTransaction { implicit session =>
          dao.imageFilesForImage(image, previousOwner).foreach(imageFile =>
            dao.changeOwner(imageFile, newOwner))
        }

    }

    case AddDataset(metaInformation, dataset, fileName, owner) =>
      val image = datasetToImage(dataset)
      val imageFile = ImageFile(image, FileName(fileName), Owner(owner))
      db.withSession { implicit session =>
        dao.insert(imageFile)
      }
      sender ! DatasetAdded(imageFile)

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

object DicomMetaDataActor {
  def props(dbProps: DbProps): Props = Props(new DicomMetaDataActor(dbProps))
}
