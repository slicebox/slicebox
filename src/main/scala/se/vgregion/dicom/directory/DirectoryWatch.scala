package se.vgregion.dicom.directory

import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds._
import java.nio.file.attribute.BasicFileAttributes
import scala.collection.JavaConversions.asScalaBuffer
import com.typesafe.scalalogging.LazyLogging
import akka.actor.ActorRef
import se.vgregion.dicom.DicomProtocol.FileAddedToWatchedDirectory

class DirectoryWatch(notifyActor: ActorRef) extends Runnable with LazyLogging {

  private val watchService = FileSystems.getDefault.newWatchService()

  def watchRecursively(root: Path) = {
    watch(root)
    Files.walkFileTree(root, new SimpleFileVisitor[Path] {
      override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes) = {
        watch(dir)
        FileVisitResult.CONTINUE
      }
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        super.visitFile(file, attrs)
        notifyActor ! FileAddedToWatchedDirectory(file)
        FileVisitResult.CONTINUE
      }
    })
  }

  private def watch(path: Path) =
    path.register(watchService, ENTRY_CREATE)

  def run() = {

    try {
      logger.debug("Directory watcher waiting for file system events")
      while (!Thread.currentThread().isInterrupted) {
        val key = watchService.take()
        key.pollEvents() foreach {
          event =>
            logger.debug(s"Recevied event: $event")
            
            val relativePath = event.context().asInstanceOf[Path]
            val path = key.watchable().asInstanceOf[Path].resolve(relativePath)
            event.kind() match {

              case ENTRY_CREATE =>

                if (Files.isDirectory(path))
                  watchRecursively(path)
                else
                  notifyActor ! FileAddedToWatchedDirectory(path)

              case x =>

                logger.warn(s"Unknown event $x")
            }
        }
        key.reset()
      }
    } catch {
      case e: InterruptedException =>
        logger.debug("Directory watcher interrupted, shutting down")
    } finally {
      watchService.close()
    }

  }

}