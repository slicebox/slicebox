package se.vgregion.filesystem

import akka.actor.Actor
import akka.event.{ LoggingReceive, Logging }
import DirectoryWatchProtocol._
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import akka.actor.ActorRef
import se.vgregion.dicom.DicomProtocol._
import akka.actor.Props

class DirectoryWatchActor(directory: Path, dicomActor: ActorRef) extends Actor {
  val log = Logging(context.system, this)
  val watchServiceTask = new DirectoryWatchService(self)
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
    case Created(path) =>
      if (Files.isRegularFile(path))
        dicomActor ! AddDicomFile(path)
    case Deleted(path) =>
      // nothing at this time
  }
}

object DirectoryWatchActor {
  def props(directory: Path, dicomActor: ActorRef): Props = Props(new DirectoryWatchActor(directory, dicomActor))
}
