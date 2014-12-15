package se.vgregion.dicom.directory

import java.nio.file.Path
import spray.json.DefaultJsonProtocol

object DirectoryWatchProtocol {

  // incoming

  case class MonitorDir(directory: String)

  // outgoing

  case class MonitorDirFailed(reason: String)
  
  case class MonitoringDir(directory: String)
  
  case class Created(fileOrDir: Path)

  case class Deleted(fileOrDir: Path)
  
  // JSON

  object MonitorDir extends DefaultJsonProtocol {
    implicit val format = jsonFormat1(MonitorDir.apply)
  }

  object MonitoringDir extends DefaultJsonProtocol {
    implicit val format = jsonFormat1(MonitoringDir.apply)
  }

}