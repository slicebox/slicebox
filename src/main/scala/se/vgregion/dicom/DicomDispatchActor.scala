package se.vgregion.dicom

import java.nio.file.Path
import java.nio.file.Paths

import scala.language.postfixOps

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.event.LoggingReceive

import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.util.EventErrorMessage
import se.vgregion.util.EventInfoMessage
import se.vgregion.util.EventWarningMessage

class DicomDispatchActor(directoryService: ActorRef, scpService: ActorRef, storage: Path, dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val metaDataActor = context.actorOf(DicomMetaDataActor.props(dbProps), name = "MetaData")

  val fileStorageActor = context.actorOf(DicomFileStorageActor.props(storage), name = "FileStorage")

  def receive = LoggingReceive {

    // to directory watches
    case msg: DirectoryRequest =>
      directoryService forward msg

    // from directory watches
    case FileAddedToWatchedDirectory(filePath) =>
      fileStorageActor ! StoreFile(filePath)
      context.become(waitingForFileStorage(sender))

    // to scp
    case msg: ScpRequest =>
      scpService forward msg

    // from scp
    case DatasetReceivedByScp(metaInformation, dataset) =>
      fileStorageActor ! StoreDataset(metaInformation, dataset)
      context.become(waitingForFileStorage(sender))

    case msg: MetaDataRequest =>
      metaDataActor forward msg
  }

  def waitingForFileStorage(client: ActorRef) = LoggingReceive {
    case FileStored(filePath, metaInformation, dataset) =>
      metaDataActor ! AddDataset(metaInformation, dataset, filePath.getFileName.toString, owner = "")
      context.become(waitingForMetaData(client))
    case FileDeleted(filePath) =>
      client ! EventInfoMessage("Deleted file: " + filePath.getFileName.toString)
  }

  def waitingForMetaData(client: ActorRef) = LoggingReceive {
    // from metadata
    case DatasetAdded(imageFile) =>
      client ! EventInfoMessage("Added imageFile: " + imageFile)
    case ImageFilesDeleted(imageFiles) =>
      imageFiles.map(imageFile => Paths.get(imageFile.fileName.value)) foreach (path => fileStorageActor ! DeleteFile(path))
  }
}

object DicomDispatchActor {
  def props(directoryService: ActorRef, scpService: ActorRef, storage: Path, dbProps: DbProps): Props = Props(new DicomDispatchActor(directoryService, scpService, storage, dbProps))
}
