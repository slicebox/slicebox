package se.vgregion.dicom.scp

import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomDispatchProtocol._
import akka.event.LoggingReceive

class ScpDataDbActor(dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new ScpDataDAO(dbProps.driver)

  def receive = LoggingReceive {
    case Initialize =>
      db.withSession { implicit session =>
        dao.create
      }
      sender ! Initialized

    case AddScp(scpData) =>
      db.withSession { implicit session =>
        dao.insert(scpData)
      }
      sender ! ScpAdded(scpData)
      
    case RemoveScp(scpData) =>
      db.withSession { implicit session =>
        dao.removeByName(scpData.name)
      }
      sender ! ScpRemoved(scpData)
      
    case GetScpDataCollection =>
      db.withSession { implicit session =>
        sender ! ScpDataCollection(dao.list)
      }

  }

}

object ScpDataDbActor {
  def props(dbProps: DbProps): Props = Props(new ScpDataDbActor(dbProps))
}
