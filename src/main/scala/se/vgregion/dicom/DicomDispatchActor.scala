package se.vgregion.dicom

import java.nio.file.Path

import scala.language.postfixOps

import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive

import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.dicom.directory.DirectoryWatchServiceActor
import se.vgregion.dicom.scp.ScpServiceActor

class DicomDispatchActor(storage: Path, dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val directoryService = context.actorOf(DirectoryWatchServiceActor.props(dbProps, storage), "DirectoryService")

  val scpService = context.actorOf(ScpServiceActor.props(dbProps, storage), "ScpService")

  val storageActor = context.actorOf(DicomStorageActor.props(dbProps, storage), name = "Storage")

  def receive = LoggingReceive {

    case msg: DirectoryRequest =>
      directoryService forward msg

    case msg: ScpRequest =>
      scpService forward msg

    case msg: MetaDataQuery =>
      storageActor forward msg

    case msg: MetaDataUpdate =>
      storageActor forward msg

    case FileAddedToWatchedDirectory(filePath) =>
      storageActor ! StoreFile(filePath)

    case DatasetReceivedByScp(dataset) =>
      storageActor ! StoreDataset(dataset)

  }

}

object DicomDispatchActor {
  def props(storage: Path, dbProps: DbProps): Props = Props(new DicomDispatchActor(storage, dbProps))
}
