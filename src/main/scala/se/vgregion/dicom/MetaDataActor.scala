package se.vgregion.dicom

import akka.actor.Actor
import akka.actor.ActorRef
import akka.event.Logging
import akka.event.LoggingReceive
import MetaDataProtocol._
import se.vgregion.dicom.MetaDataProtocol.AddMetaData
import se.vgregion.dicom.MetaDataProtocol.DeleteMetaData
import se.vgregion.dicom.MetaDataProtocol.GetMetaDataCollection
import se.vgregion.db.DbProtocol._

class MetaDataActor(dbActor: ActorRef) extends Actor {
  val log = Logging(context.system, this)

  def receive = LoggingReceive {
    case AddMetaData(path) =>
      DicomUtil.readMetaData(path).foreach(metaData => dbActor ! InsertMetaData(metaData))
    case DeleteMetaData(path) =>
      DicomUtil.readMetaData(path).foreach(metaData => dbActor ! RemoveMetaData(path.toAbsolutePath().toString()))
    case GetMetaDataCollection =>
      dbActor forward GetMetaDataEntries
    case GetPatients =>
      dbActor forward GetPatientEntries
  }
  
}