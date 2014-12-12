package se.vgregion.db

import akka.actor.Actor
import DbProtocol._
import se.vgregion.dicom.ScpProtocol._
import se.vgregion.dicom.MetaDataProtocol._
import scala.slick.jdbc.JdbcBackend.Database
import akka.actor.Props

class DbActor(db: Database, dao: DAO) extends Actor {

  def receive = {
    case CreateTables =>
      db.withSession { implicit session =>
        dao.scpDataDAO.create
        dao.metaDataDAO.create
        dao.userDAO.create
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
    case RemoveImage(image) =>
      db.withSession { implicit session =>
        dao.metaDataDAO.deleteImage(image)
      }
    case GetScpDataEntries =>
      db.withSession { implicit session =>
        sender ! ScpDataCollection(dao.scpDataDAO.list)
      }
    case GetImageFileEntries =>
      db.withSession { implicit session =>
        sender ! ImageFiles(dao.metaDataDAO.allImageFiles)
      }
    case GetPatientEntries =>
      db.withSession { implicit session =>
        sender ! Patients(dao.metaDataDAO.allPatients)
      }
    case GetStudyEntries(patient) =>
      db.withSession { implicit session =>
        sender ! Studies(dao.metaDataDAO.studiesForPatient(patient))
      }
    case GetSeriesEntries(study) =>
      db.withSession { implicit session =>
        sender ! SeriesCollection(dao.metaDataDAO.seriesForStudy(study))
      }
    case GetImageEntries(series) =>
      db.withSession { implicit session =>
        sender ! Images(dao.metaDataDAO.imagesForSeries(series))
      }
    case GetImageFileEntries(image) =>
      db.withSession { implicit session =>
        sender ! ImageFiles(dao.metaDataDAO.imageFilesForImage(image))
      }
    case GetUserByName(name) =>
      db.withSession { implicit session =>
        sender ! dao.userDAO.findUserByName(name)
      }
    case GetUserNames =>
      db.withSession { implicit session =>
        sender ! dao.userDAO.listUserNames
      }
    case AddUser(apiUser) =>
      db.withSession { implicit session =>
        sender ! dao.userDAO.insert(apiUser)
      }
    case DeleteUser(userName) =>
      db.withSession { implicit session =>
        sender ! dao.userDAO.delete(userName)
      }
  }

}

object DbActor {
  def props(db: Database, dao: DAO): Props = Props(new DbActor(db, dao))
}
