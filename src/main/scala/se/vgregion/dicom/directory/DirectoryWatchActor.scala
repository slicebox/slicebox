package se.vgregion.dicom.directory

import java.nio.file.Files
import java.nio.file.Path
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.dicom.DicomProtocol.FileAddedToWatchedDirectory
import se.vgregion.dicom.DicomProtocol.FileRemovedFromWatchedDirectory

class DirectoryWatchActor(directory: Path) extends Actor {
  val log = Logging(context.system, this)
  val watchServiceTask = new DirectoryWatch(self)
  val watchThread = new Thread(watchServiceTask, "WatchService")

  override def preStart() {
    watchThread.setDaemon(true)
    watchThread.start()
    watchServiceTask watchRecursively directory
  }

  override def postStop() {
    watchThread.interrupt()
  }

  def receive = LoggingReceive {
    case FileAddedToWatchedDirectory(path) =>
      if (Files.isRegularFile(path))
        context.parent ! FileAddedToWatchedDirectory(path)
    case FileRemovedFromWatchedDirectory(path) =>
      // nothing at this time
  }
}

object DirectoryWatchActor {
  def props(directory: Path): Props = Props(new DirectoryWatchActor(directory))
}
