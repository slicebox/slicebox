package se.nimsa.sbx.directory

import se.nimsa.sbx.model.Entity
import java.nio.file.Path

object DirectoryWatchProtocol {
  
  case class WatchedDirectory(id: Long, name: String, path: String) extends Entity

    
  sealed trait DirectoryRequest
  
  case class WatchDirectory(name: String, path: String) extends DirectoryRequest

  case class UnWatchDirectory(id: Long) extends DirectoryRequest

  case object GetWatchedDirectories extends DirectoryRequest
    
  case class GetWatchedDirectoryById(watchedDirectoryId: Long) extends DirectoryRequest
  
  case class WatchedDirectories(directories: Seq[WatchedDirectory])
  
  
  case class DirectoryUnwatched(id: Long)

  case class FileAddedToWatchedDirectory(filePath: Path)

}