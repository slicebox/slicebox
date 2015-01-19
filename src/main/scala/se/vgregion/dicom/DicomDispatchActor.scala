package se.vgregion.dicom

import java.nio.file.Path
import java.nio.file.Paths
import scala.language.postfixOps
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.actor.Stash
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.util.EventErrorMessage
import se.vgregion.util.EventInfoMessage
import se.vgregion.util.EventWarningMessage
import se.vgregion.dicom.directory.DirectoryWatchServiceActor
import se.vgregion.dicom.scp.ScpServiceActor

class DicomDispatchActor(storage: Path, dbProps: DbProps) extends Actor with Stash {
  val log = Logging(context.system, this)

  val directoryService = context.actorOf(DirectoryWatchServiceActor.props(dbProps, storage), "DirectoryService")
  
  val scpService = context.actorOf(ScpServiceActor.props(dbProps, storage), "ScpService")
  
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

    case msg: MetaDataQuery =>
      metaDataActor forward msg
      
    case msg: MetaDataUpdate => msg match {
      case msg: ChangeOwner =>
        metaDataActor forward msg
        
      case msg: DeleteImage =>
        metaDataActor ! msg
        context.become(waitingForMetaData(sender))
        
      case msg: DeleteSeries =>
        metaDataActor ! msg
        context.become(waitingForMetaData(sender))

      case msg: DeleteStudy =>
        metaDataActor ! msg
        context.become(waitingForMetaData(sender))

      case msg: DeletePatient =>
        metaDataActor ! msg
        context.become(waitingForMetaData(sender))
    }
    
  }

  def waitingForFileStorage(client: ActorRef): Receive = LoggingReceive {
    case FileStored(filePath, metaInformation, dataset) =>
      metaDataActor ! AddDataset(metaInformation, dataset, filePath.getFileName.toString, owner = "")
      context.unbecome()
      context.become(waitingForMetaData(client))
    case FilesDeleted(filePaths) =>
      filePaths.foreach(path => log.info("Deleted file " + path))
      unstashAll()
      context.unbecome()
    case other => stash()
  }

  def waitingForMetaData(client: ActorRef): Receive = LoggingReceive {
    case DatasetAdded(imageFile) =>
      log.info("Added image file: " + imageFile)
      unstashAll()
      context.unbecome()
    case ImageFilesDeleted(imageFiles) =>
      val paths = imageFiles.map(imageFile => Paths.get(imageFile.fileName.value))
      fileStorageActor ! DeleteFiles(paths)
      context.unbecome()
      context.become(waitingForFileStorage(client))
    case other => stash()
  }
}

object DicomDispatchActor {
  def props(storage: Path, dbProps: DbProps): Props = Props(new DicomDispatchActor(storage, dbProps))
}
