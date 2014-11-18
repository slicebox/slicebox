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
import se.vgregion.dicom.MetaDataProtocol.DeleteMetaData

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
    case MonitorDir(directoryName) =>
      try {
        var directory = Paths.get(directoryName)
        if (watchedDirectories.contains(directory))
          sender ! MonitorDirFailed(s"Directory $directoryName already monitored")
        else {
          watchServiceTask watchRecursively directory
          watchedDirectories = watchedDirectories :+ directory
          sender ! MonitoringDir(directoryName)
        }
      } catch {
        case e: Exception => sender ! MonitorDirFailed(e.getMessage)
      }
    case Created(path) =>
      DicomUtil.readMetaData(path).foreach(metaData => metaDataActor ! AddMetaData(metaData))
    case Deleted(path) =>
      metaDataActor ! DeleteMetaData(null)
  }
}