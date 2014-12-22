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

  val client = context.parent

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
      context.become(waitingForInitialized)

    // to directory watches
    case WatchDirectory(pathName) =>
      directoryWatchDbActor ! AddDirectory(Paths.get(pathName))
      context.become(waitingForDirectoryDb)
    case UnWatchDirectory(pathName) =>
      directoryWatchDbActor ! RemoveDirectory(Paths.get(pathName))
      context.become(waitingForDirectoryDb)

    // from directory watches
    case FileAddedToWatchedDirectory(filePath) =>
      fileStorageActor ! StoreFile(filePath)
      context.become(waitingForFileStorage)
      
    // to scp
    case msg: AddScp =>
      scpDataDbActor ! msg
      context.become(waitingForScpDataDb)
    case msg: RemoveScp =>
      scpService ! msg
      context.become(waitingForScpDataDb)
    case GetScpDataCollection =>
      scpDataDbActor forward GetScpDataCollection

    // from scp
    case DatasetReceivedByScp(metaInformation, dataset) =>
      fileStorageActor ! StoreDataset(metaInformation, dataset)
      context.become(waitingForFileStorage)

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
  def waitingForInitialized = LoggingReceive {
    case Initialized =>
      nInitializedReceived += 1
      if (nInitializedReceived == 4)
        client ! Message("System initialized")
  }

  def waitingForDirectoryDb = LoggingReceive {
    case DirectoryAdded(path) =>
      directoryService ! WatchDirectoryPath(path)
      context.become(waitingForDirectoryService)
    case DirectoryAlreadyAdded(path) =>
      client ! Validation("Directory already added: " + path)
    case DirectoryRemoved(path) =>
      directoryService ! UnWatchDirectoryPath(path)
      context.become(waitingForDirectoryService)
  }

  def waitingForDirectoryService = LoggingReceive {
    case DirectoryWatched(path) =>
      client ! Message("Now watching directory " + path)
    case DirectoryUnwatched(path) =>
      client ! Message("No longer watching directory " + path)
    case DirectoryWatchFailed(reason) =>
      client ! Validation(reason)
  }

  def waitingForScpDataDb = LoggingReceive {
    case ScpAdded(scpData) =>
      scpService ! AddScp(scpData)
      context.become(waitingForScpService)
    case ScpRemoved(scpData) =>
      scpService ! RemoveScp(scpData)
      context.become(waitingForScpService)
  }

  def waitingForScpService = LoggingReceive {
    case ScpAdded(scpData) =>
      client ! Message("Added SCP " + scpData.name)
    case ScpAlreadyAdded(scpData) =>
      client ! Validation(s"An SCP with name ${scpData.name} already exists")
    case ScpSetupFailed(reason) =>
      client ! Validation("SCP could not be started: " + reason)
    case ScpRemoved(scpData) =>
      client ! Message("Removed SCP " + scpData.name)
    case ScpNotFound(scpData) =>
      client ! Validation(s"An SCP with name ${scpData.name} does not exist")
  }

  def waitingForFileStorage = LoggingReceive {
    case FileStored(filePath, metaInformation, dataset) =>
      metaDataActor ! AddDataset(metaInformation, dataset, filePath.getFileName.toString, owner = "")
      context.become(waitingForMetaData)
    case FileNotStored(reason) =>
      client ! EventErrorMessage("Could not add file to storage: " + reason)
    case FileNotDeleted(reason) =>
      client ! EventWarningMessage("Could not delete file from storage: " + reason)
    case FileDeleted(filePath) =>
      client ! EventInfoMessage("Deleted file: " + filePath.getFileName.toString)
  }

  def waitingForMetaData = LoggingReceive {
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
