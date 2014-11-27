package se.vgregion.filesystem

import akka.actor.Actor
import akka.event.{ LoggingReceive, Logging }
import FileSystemProtocol._
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import akka.actor.ActorRef
import se.vgregion.dicom.DicomUtil
import se.vgregion.dicom.MetaDataProtocol._

class FileSystemActor(metaDataActor: ActorRef) extends Actor {
  val log = Logging(context.system, this)
  val watchServiceTask = new WatchServiceTask(self)
  val watchThread = new Thread(watchServiceTask, "WatchService")

  override def preStart() {
    watchThread.setDaemon(true)
    watchThread.start()
  }

  override def postStop() {
    watchThread.interrupt()
  }

  var watchedDirectories = List.empty[Path]

  def receive = LoggingReceive {
    case MonitorDir(directory) =>
      try {
        val directoryPath = Paths.get(directory)
        if (watchedDirectories.contains(directoryPath))
          sender ! MonitorDirFailed(s"Directory $directory already monitored")
        else {
          watchServiceTask watchRecursively directoryPath
          watchedDirectories = watchedDirectories :+ directoryPath
          sender ! MonitoringDir(directory)
        }
      } catch {
        case e: Exception => sender ! MonitorDirFailed(e.getMessage)
      }
    case Created(path) =>
      if (Files.isRegularFile(path))
        metaDataActor ! AddImage(path)
    case Deleted(path) =>
      if (Files.isRegularFile(path))
        metaDataActor ! DeleteImage(path)
  }
}