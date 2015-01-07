package se.vgregion.dicom.directory

import java.nio.file.Path

object DirectoryWatchProtocol {
  
  case class WatchDirectoryPath(path: Path)
  
  case class UnWatchDirectoryPath(path: Path)
  
  case class FileAddedToDirectory(fileOrDir: Path)

  case class FileRemovedFromDirectory(fileOrDir: Path)
  
  case class AddDirectory(path: Path)

  case class DirectoryAdded(path: Path)
  
  case class DirectoryAlreadyAdded(path: Path)
  
  case class RemoveDirectory(path: Path)
  
  case class DirectoryRemoved(path: Path)
  
  case class WatchedDirectoryNotFound(path: Path)
  
  case object GetWatchedDirectories
  
  case class WatchedDirectories(names: Seq[Path])
  
}