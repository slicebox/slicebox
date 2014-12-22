package se.vgregion.dicom

import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import se.vgregion.app.DbProps
import DicomMetaDataProtocol._
import DicomDispatchProtocol._
import se.vgregion.app._
import akka.event.LoggingReceive

class DicomMetaDataDbActor(dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DicomMetaDataDAO(dbProps.driver)

  def receive = LoggingReceive {
    case Initialize =>
      db.withSession { implicit session =>
        dao.create
      }
      sender ! Initialized
      
    case AddImageFile(imageFile) =>
      db.withSession { implicit session =>
        dao.insert(imageFile)
      }
      sender ! ImageFileAdded(imageFile)
      
    case DeleteImage(image, owner) =>
      db.withSession { implicit session =>
        owner match {
          case Some(o) => dao.deleteImage(image, o)
          case _ => dao.deleteImage(image)
        }
      }
      sender ! ImageDeleted(image)
      
    case DeleteSeries(series, owner) =>
      db.withSession { implicit session =>
        owner match {
          case Some(o) => dao.deleteSeries(series, o)
          case _ => dao.deleteSeries(series)
        }
      }
      sender ! SeriesDeleted(series)
      
    case DeleteStudy(study, owner) =>
      db.withSession { implicit session =>
        owner match {
          case Some(o) => dao.deleteStudy(study, o)
          case _ => dao.deleteStudy(study)
        }
      }
      sender ! StudyDeleted(study)
      
    case DeletePatient(patient, owner) =>
      db.withSession { implicit session =>
        owner match {
          case Some(o) => dao.deletePatient(patient, o)
          case _ => dao.deletePatient(patient)
        }
      }
      sender ! PatientDeleted(patient)
      
    case GetAllImageFiles(owner) =>
      db.withSession { implicit session =>
        sender ! owner.map(o => ImageFiles(dao.imageFilesForOwner(o))).getOrElse(ImageFiles(dao.allImageFiles))
      }
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
    case GetImageFiles(image, owner) =>
      db.withSession { implicit session =>
        sender ! owner.map(o => ImageFiles(dao.imageFilesForImage(image, o))).getOrElse(ImageFiles(dao.imageFilesForImage(image)))
      }
  }

}

object DicomMetaDataDbActor {
  def props(dbProps: DbProps): Props = Props(new DicomMetaDataDbActor(dbProps))
}
