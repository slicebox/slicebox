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
import se.vgregion.dicom.directory.DirectoryWatchDbActor
import se.vgregion.dicom.directory.DirectoryWatchProtocol._
import se.vgregion.dicom.scp.ScpDataDbActor
import se.vgregion.dicom.scp.ScpProtocol._
import se.vgregion.util._
import akka.actor.ActorContext

class DicomDispatchActor(directoryService: ActorRef, scpService: ActorRef, storage: Path, dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val directoryWatchDbActor = context.actorOf(DirectoryWatchDbActor.props(dbProps), name = "DirectoryWatchDb")

  val scpDataDbActor = context.actorOf(ScpDataDbActor.props(dbProps), name = "ScpDataDb")

  val metaDataActor = context.actorOf(DicomMetaDataActor.props(dbProps), name = "MetaData")

  val fileStorageActor = context.actorOf(DicomFileStorageActor.props(storage), name = "FileStorage")

  def receive = LoggingReceive {

    case Initialize =>
      metaDataActor ! Initialize
      scpDataDbActor ! Initialize
      directoryWatchDbActor ! Initialize
      fileStorageActor ! Initialize
      context.become(waitingForInitialized(sender))

    // to directory watches
    case WatchDirectory(pathName) =>
      directoryWatchDbActor ! AddDirectory(Paths.get(pathName))
      context.become(waitingForDirectoryDb(sender))
    case UnWatchDirectory(pathName) =>
      directoryWatchDbActor ! RemoveDirectory(Paths.get(pathName))
      context.become(waitingForDirectoryDb(sender))

    // from directory watches
    case FileAddedToWatchedDirectory(filePath) =>
      fileStorageActor ! StoreFile(filePath)
      context.become(waitingForFileStorage(sender))

    // to scp
    case msg: AddScp =>
      scpDataDbActor ! msg
      context.become(waitingForScpDataDb(sender))
    case msg: RemoveScp =>
      scpDataDbActor ! msg
      context.become(waitingForScpDataDb(sender))
    case GetScpDataCollection =>
      scpDataDbActor forward GetScpDataCollection

    // from scp
    case DatasetReceivedByScp(metaInformation, dataset) =>
      fileStorageActor ! StoreDataset(metaInformation, dataset)
      context.become(waitingForFileStorage(sender))

    case msg: GetAllImages =>
      metaDataActor forward msg
    case msg: GetImages =>
      metaDataActor forward msg
    case msg: GetPatients =>
      metaDataActor forward msg
    case msg: GetStudies =>
      metaDataActor forward msg
    case msg: GetSeries =>
      metaDataActor forward msg
    case msg: DeleteImage =>
      metaDataActor forward msg
    case msg: DeleteSeries =>
      metaDataActor forward msg
    case msg: DeleteStudy =>
      metaDataActor forward msg
    case msg: DeletePatient =>
      metaDataActor forward msg
    case msg: ChangeOwner =>
      metaDataActor forward msg
  }

  var nInitializedReceived = 0 // improve?
  def waitingForInitialized(client: ActorRef) = LoggingReceive {
    case Initialized =>
      nInitializedReceived += 1
      if (nInitializedReceived == 4) {
        directoryWatchDbActor ! GetWatchedDirectories        
        scpDataDbActor ! GetScpDataCollection
        context.become(waitingForDicomSourcesDuringInitialization(client))
      }
  }

  var directoriesInitialized = false
  var scpsInitialized = false
  def waitingForDicomSourcesDuringInitialization(client: ActorRef) = LoggingReceive {
    case WatchedDirectories(paths) =>
      paths foreach (directoryService ! WatchDirectoryPath(_))
      directoriesInitialized = true
      if (scpsInitialized) client ! Initialized
    case ScpDataCollection(scpDatas) =>
      scpDatas foreach (scpService ! AddScp(_))
      scpsInitialized = true
      if (directoriesInitialized) client ! Initialized
  }
  
  def waitingForDirectoryDb(client: ActorRef) = LoggingReceive {
    case DirectoryAdded(path) =>
      directoryService ! WatchDirectoryPath(path)
      context.become(waitingForDirectoryService(client))
    case DirectoryAlreadyAdded(path) =>
      client ! ClientError("Directory already added: " + path)
    case DirectoryRemoved(path) =>
      directoryService ! UnWatchDirectoryPath(path)
      context.become(waitingForDirectoryService(client))
  }

  def waitingForDirectoryService(client: ActorRef) = LoggingReceive {
    case msg: DirectoryWatched =>
      client ! msg
    case msg: DirectoryUnwatched =>
      client ! msg
    case msg: DirectoryWatchFailed =>
      client ! msg
  }

  def waitingForScpDataDb(client: ActorRef) = LoggingReceive {
    case ScpAdded(scpData) =>
      scpService ! AddScp(scpData)
      context.become(waitingForScpService(client))
    case ScpRemoved(scpData) =>
      scpService ! RemoveScp(scpData)
      context.become(waitingForScpService(client))
  }

  def waitingForScpService(client: ActorRef) = LoggingReceive {
    case msg: ScpAdded =>
      client ! msg
    case ScpAlreadyAdded(scpData) =>
      client ! ClientError(s"An SCP with name ${scpData.name} already exists")
    case ScpSetupFailed(reason) =>
      client ! ClientError("SCP could not be started: " + reason)
    case msg: ScpRemoved =>
      client ! msg
    case ScpNotFound(scpData) =>
      client ! ClientError(s"An SCP with name ${scpData.name} does not exist")
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
