package se.vgregion.db

import akka.actor.Actor
import DbProtocol._
import se.vgregion.dicom.ScpProtocol._

class DbActor(db: DbOps) extends Actor {
   
  def receive = {
    case InsertScpData(scpData: ScpData) =>
      db.insertScpData(scpData)
    case GetScpDataEntries =>
      sender ! ScpDataCollection(db.scpDataEntries)
  }
  
}