package se.vgregion.util

import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException

object TestUtil {

  def deleteFolder(path: Path) =
    Files.walkFileTree(path, new SimpleFileVisitor[Path]() {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.delete(file)
        FileVisitResult.CONTINUE
      }
      override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = {
        // try to delete the file anyway, even if its attributes could not be read, since delete-only access is theoretically possible
        Files.delete(file)
        FileVisitResult.CONTINUE
      }
      override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult =
        if (exc == null) {
          Files.delete(dir)
          FileVisitResult.CONTINUE
        } else {
          // directory iteration failed; propagate exception
          throw exc
        }
    })

}