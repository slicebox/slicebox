package se.vgregion.db

import akka.actor.Actor
import DbProtocol._
import se.vgregion.dicom.ScpProtocol._
import se.vgregion.dicom.MetaDataProtocol._
import scala.slick.jdbc.JdbcBackend.Database

class DbActor(db: Database, dao: DAO) extends Actor {

  def receive = {
    case CreateTables =>
      db.withSession { implicit session =>
        dao.scpDataDAO.create
        dao.metaDataDAO.create
      }
    case InsertScpData(scpData) =>
      db.withSession { implicit session =>
        dao.scpDataDAO.insert(scpData)
      }
    case InsertImageFile(imageFile) =>
      db.withSession { implicit session =>
        dao.metaDataDAO.insert(imageFile)
      }
    case RemoveScpData(name) =>
      db.withSession { implicit session =>
        dao.scpDataDAO.removeByName(name)
      }
    case RemoveImageFile(fileName) =>
      db.withSession { implicit session =>
        dao.metaDataDAO.removeByFileName(fileName)
      }
    case GetScpDataEntries =>
      db.withSession { implicit session =>
        sender ! ScpDataCollection(dao.scpDataDAO.list)
      }
    case GetImageFileEntries =>
      db.withSession { implicit session =>
        sender ! ImageFiles(dao.metaDataDAO.list)
      }
    case GetPatientEntries =>
      db.withSession { implicit session =>
        sender ! Patients(dao.metaDataDAO.listPatients)
      }
    case GetStudyEntries(patient) =>
      db.withSession { implicit session =>
        sender ! Studies(dao.metaDataDAO.listStudiesForPatient(patient))
      }
    case GetSeriesEntries(study) =>
      db.withSession { implicit session =>
        sender ! SeriesCollection(dao.metaDataDAO.listSeriesForStudy(study))
      }
    case GetImageEntries(series) =>
      db.withSession { implicit session =>
        sender ! Images(dao.metaDataDAO.listImagesForSeries(series))
      }
    case GetImageFileEntries(image) =>
      db.withSession { implicit session =>
        sender ! ImageFiles(dao.metaDataDAO.listImageFilesForImage(image))
      }
  }

}