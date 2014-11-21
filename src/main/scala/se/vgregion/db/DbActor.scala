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
    case InsertMetaData(metaData) =>
      db.withSession { implicit session =>
        dao.metaDataDAO.insert(metaData)
      }
    case RemoveScpData(name) =>
      db.withSession { implicit session =>
        dao.scpDataDAO.removeByName(name)
      }
    case RemoveMetaData(fileName) =>
      db.withSession { implicit session =>
        dao.metaDataDAO.removeByFileName(fileName)
      }
    case GetScpDataEntries =>
      db.withSession { implicit session =>
        sender ! ScpDataCollection(dao.scpDataDAO.list)
      }
    case GetMetaDataEntries =>
      db.withSession { implicit session =>
        sender ! MetaDataCollection(dao.metaDataDAO.list)
      }
    case GetPatientEntries =>
      db.withSession { implicit session =>
        sender ! Patients(dao.metaDataDAO.listPatients)
      }
  }

}