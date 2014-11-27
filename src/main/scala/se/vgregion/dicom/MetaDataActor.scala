package se.vgregion.dicom

import akka.actor.Actor
import akka.actor.ActorRef
import akka.event.Logging
import akka.event.LoggingReceive
import MetaDataProtocol._
import se.vgregion.dicom.MetaDataProtocol._
import se.vgregion.db.DbProtocol._

class MetaDataActor(dbActor: ActorRef) extends Actor {
  val log = Logging(context.system, this)

  def receive = LoggingReceive {
    case AddImage(path) =>
      DicomUtil.readImage(path).foreach(image => dbActor ! InsertImage(image))
    case DeleteImage(path) =>
      DicomUtil.readImage(path).foreach(image => dbActor ! RemoveImage(path.toAbsolutePath().toString()))
    case GetImages =>
      dbActor forward GetImageEntries
    case GetPatients =>
      dbActor forward GetPatientEntries
    case GetStudies(patient) =>
      dbActor forward GetStudyEntries(patient)
    case GetSeries(study) =>
      dbActor forward GetSeriesEntries(study)
    case GetImages(series) =>
      dbActor forward GetImageEntries(series)
  }
  
}