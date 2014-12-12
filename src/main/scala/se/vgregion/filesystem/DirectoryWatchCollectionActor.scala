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

class DirectoryWatchCollectionActor(dicomActor: ActorRef) extends Actor {
  val log = Logging(context.system, this)

  var watchedDirectories = List.empty[Path]

  def receive = LoggingReceive {
    case MonitorDir(directory) =>
      try {
        val directoryPath = Paths.get(directory)
        if (watchedDirectories.contains(directoryPath))
          sender ! MonitorDirFailed(s"Directory $directory already monitored")
        else {
          context.actorOf(DirectoryWatchActor.props(directoryPath, dicomActor), directoryPath.getFileName.toString)
          watchedDirectories = watchedDirectories :+ directoryPath
          sender ! MonitoringDir(directory)
        }
      } catch {
        case e: Exception => sender ! MonitorDirFailed(e.getMessage)
      }
    case Created(path) =>
      if (Files.isRegularFile(path))
        dicomActor ! AddDicomFile(path)
    case Deleted(path) =>
    // nothing at this time
  }
}

object DirectoryWatchCollectionActor {
  def props(dicomActor: ActorRef): Props = Props(new DirectoryWatchCollectionActor(dicomActor))
}