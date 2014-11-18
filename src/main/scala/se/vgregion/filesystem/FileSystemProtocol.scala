package se.vgregion.filesystem

import java.nio.file.Path
import spray.json.DefaultJsonProtocol

object FileSystemProtocol {

  case class FileName(name: String)
  
  // incoming

  case class MonitorDir(dir: String)

  // outgoing

  case class MonitorDirFailed(reason: String)
  
  case class MonitoringDir(dir: String)
  
  case class Created(fileOrDir: Path)

  case class Deleted(fileOrDir: Path)
  
  // JSON

  object FileName extends DefaultJsonProtocol {
    implicit val format = jsonFormat1(FileName.apply)
  }

  object MonitorDir extends DefaultJsonProtocol {
    implicit val format = jsonFormat1(MonitorDir.apply)
  }

  object MonitoringDir extends DefaultJsonProtocol {
    implicit val format = jsonFormat1(MonitoringDir.apply)
  }

}