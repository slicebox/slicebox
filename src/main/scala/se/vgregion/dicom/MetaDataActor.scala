package se.vgregion.dicom

import akka.actor.Actor
import akka.actor.ActorRef
import akka.event.Logging
import akka.event.LoggingReceive
import MetaDataProtocol._
import se.vgregion.dicom.MetaDataProtocol.AddMetaData
import se.vgregion.dicom.MetaDataProtocol.DeleteMetaData
import se.vgregion.dicom.MetaDataProtocol.GetMetaDataCollection

class MetaDataActor(dbActor: ActorRef) extends Actor {
  val log = Logging(context.system, this)

  def receive = LoggingReceive {
    case AddMetaData(metaData) =>
      log.info(metaData.toString)
    case DeleteMetaData(metaData) =>
      log.info(metaData.toString)
    case GetMetaDataCollection =>
      log.info("collection")
  }
  
}