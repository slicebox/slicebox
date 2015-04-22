package se.nimsa.sbx.dicom

import java.nio.file.Path
import scala.language.postfixOps
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.dicom.directory.DirectoryWatchServiceActor
import se.nimsa.sbx.dicom.scp.ScpServiceActor
import se.nimsa.sbx.dicom.scu.ScuServiceActor
import se.nimsa.sbx.dicom.DicomProtocol._

class DicomDispatchActor(storage: Path, dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val directoryService = context.actorOf(DirectoryWatchServiceActor.props(dbProps, storage), "DirectoryService")

  val scpService = context.actorOf(ScpServiceActor.props(dbProps), "ScpService")

  val scuService = context.actorOf(ScuServiceActor.props(dbProps, storage), "ScuService")

  val storageActor = context.actorOf(DicomStorageActor.props(dbProps, storage).withDispatcher("akka.prio-dispatcher"), name = "Storage")

  def receive = LoggingReceive {

    case msg: DirectoryRequest =>
      directoryService forward msg

    case msg: ScpRequest =>
      scpService forward msg

    case msg: ScuRequest =>
      scuService forward msg

    case msg: MetaDataQuery =>
      storageActor forward msg

    case msg: MetaDataUpdate =>
      storageActor forward msg

    case msg: ImageRequest =>
      storageActor forward msg

    case msg: AddDataset =>
      storageActor forward msg
      
  }

}

object DicomDispatchActor {
  def props(storage: Path, dbProps: DbProps): Props = Props(new DicomDispatchActor(storage, dbProps))
}
