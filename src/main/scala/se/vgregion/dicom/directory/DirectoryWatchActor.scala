package se.vgregion.dicom.directory

import java.nio.file.Files
import java.nio.file.Path
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.dicom.DicomUtil._
import se.vgregion.dicom.DicomProtocol.DatasetReceived
import se.vgregion.dicom.DicomProtocol.FileAddedToWatchedDirectory
import org.dcm4che3.data.Tag
import java.nio.file.Paths

class DirectoryWatchActor(directoryPath: String) extends Actor {
  val log = Logging(context.system, this)

  val watchServiceTask = new DirectoryWatch(self)

  val watchThread = new Thread(watchServiceTask, "WatchService")

  override def preStart() {
    watchThread.setDaemon(true)
    watchThread.start()
    watchServiceTask watchRecursively Paths.get(directoryPath)
  }

  override def postStop() {
    watchThread.interrupt()
  }

  def receive = LoggingReceive {
    case FileAddedToWatchedDirectory(path) =>
      if (Files.isRegularFile(path)) {
        val dataset = loadDataset(path, true)
        if (dataset != null)
          if (checkSopClass(dataset))
            context.system.eventStream.publish(DatasetReceived(dataset))
          else
            log.info(s"Received file with unsupported SOP Class UID ${dataset.getString(Tag.SOPClassUID)}, skipping")
        else
          log.info(s"File $path is not a DICOM file")
      }
  }
}

object DirectoryWatchActor {
  def props(directoryPath: String): Props = Props(new DirectoryWatchActor(directoryPath))
}
