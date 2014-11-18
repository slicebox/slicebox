package se.vgregion.db

import akka.actor.Actor
import DbProtocol._
import se.vgregion.dicom.ScpProtocol._

class DbActor(db: DbOps) extends Actor {
   
  def receive = {
    case InsertScpData(scpData) =>
      db.insertScpData(scpData)
    case RemoveScpData(name) =>
      db.removeScpDataByName(name)
    case GetScpDataEntries =>
      sender ! ScpDataCollection(db.scpDataEntries)
  }
  
}