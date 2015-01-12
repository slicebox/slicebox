package se.vgregion.dicom

import java.nio.file.Path
import java.nio.file.Paths
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import akka.util.Timeout
import se.vgregion.app._
import se.vgregion.dicom.DicomDispatchProtocol._
import se.vgregion.util._
import akka.actor.ActorContext

class DicomDispatchActor(directoryService: ActorRef, scpService: ActorRef, storage: Path, dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val metaDataActor = context.actorOf(DicomMetaDataActor.props(dbProps), name = "MetaData")

  val fileStorageActor = context.actorOf(DicomFileStorageActor.props(storage), name = "FileStorage")

  def receive = LoggingReceive {

    case Initialize =>
      metaDataActor ! Initialize
      fileStorageActor ! Initialize
      context.become(waitingForInitialized(sender))

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

  var nInitializedReceived = 0 // improve?
  def waitingForInitialized(client: ActorRef) = LoggingReceive {
    case Initialized =>
      nInitializedReceived += 1
      if (nInitializedReceived == 2) {
        directoryService ! GetWatchedDirectories        
        scpService ! GetScpDataCollection
        context.become(waitingForDicomSourcesDuringInitialization(client))
      }
  }

  var directoriesInitialized = false
  var scpsInitialized = false
  def waitingForDicomSourcesDuringInitialization(client: ActorRef) = LoggingReceive {
    case WatchedDirectories(paths) =>
      paths foreach (path => directoryService ! WatchDirectory(path.toString))
      directoriesInitialized = true
      if (scpsInitialized) client ! Initialized
    case ScpDataCollection(scpDatas) =>
      scpDatas foreach (scpService ! AddScp(_))
      scpsInitialized = true
      if (directoriesInitialized) client ! Initialized
  }
  
  def waitingForFileStorage(client: ActorRef) = LoggingReceive {
    case FileStored(filePath, metaInformation, dataset) =>
      metaDataActor ! AddDataset(metaInformation, dataset, filePath.getFileName.toString, owner = "")
      context.become(waitingForMetaData(client))
    case FileNotStored(reason) =>
      client ! EventErrorMessage("Could not add file to storage: " + reason)
    case FileNotDeleted(reason) =>
      client ! EventWarningMessage("Could not delete file from storage: " + reason)
    case FileDeleted(filePath) =>
      client ! EventInfoMessage("Deleted file: " + filePath.getFileName.toString)
  }

  def waitingForMetaData(client: ActorRef) = LoggingReceive {
    // from metadata
    case DatasetAdded(imageFile) =>
      client ! EventInfoMessage("Added imageFile: " + imageFile)
    case ImageFilesDeleted(imageFiles) =>
      imageFiles.map(imageFile => Paths.get(imageFile.fileName.value)) foreach (path => fileStorageActor ! DeleteFile(path))
    // apiActor ! ImagesDeleted(imageFiles.map(imageFile => imageFile.image))
    case DatasetNotAdded(reason) =>
      client ! EventErrorMessage("Could not add dataset metadata: " + reason)
  }
}

object DicomDispatchActor {
  def props(directoryService: ActorRef, scpService: ActorRef, storage: Path, dbProps: DbProps): Props = Props(new DicomDispatchActor(directoryService, scpService, storage, dbProps))
}
