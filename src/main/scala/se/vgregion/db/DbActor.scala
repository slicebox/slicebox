package se.vgregion.db

import akka.actor.Actor
import DbProtocol._
import se.vgregion.dicom.ScpProtocol._
import se.vgregion.dicom.MetaDataProtocol._

class DbActor(db: DbOps) extends Actor {
   
  def receive = {
    case InsertScpData(scpData) =>
      db.insertScpData(scpData)
    case InsertMetaData(metaData) =>
      db.insertMetaData(metaData)
    case RemoveScpData(name) =>
      db.removeScpDataByName(name)
    case RemoveMetaData(fileName) =>
      db.removeMetaDataByFileName(fileName)
    case GetScpDataEntries =>
      sender ! ScpDataCollection(db.scpDataEntries)
    case GetMetaDataEntries =>
      sender ! MetaDataCollection(db.metaDataEntries)
  }
  
}