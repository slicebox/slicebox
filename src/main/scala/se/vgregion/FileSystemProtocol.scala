package se.vgregion

import java.nio.file.Path
import java.io.File
import spray.json.DefaultJsonProtocol

object FileSystemProtocol {

  // incoming

  case class MonitorDir(dir: String)

  case object GetFileNames
  
  // outgoing

  case object MonitoringDir
  
  case class Created(fileOrDir: File)

  case class Deleted(fileOrDir: File)
  
  case class FileName(name: String)

  case class FileNames(files: List[FileName])

  // JSON

  object FileName extends DefaultJsonProtocol {
    implicit val format = jsonFormat1(FileName.apply)
  }

  object MonitorDir extends DefaultJsonProtocol {
    implicit val format = jsonFormat1(MonitorDir.apply)
  }

}