package se.vgregion

import akka.actor.ActorRef
import java.nio.file._
import java.nio.file.StandardWatchEventKinds._
import java.nio.file.attribute.BasicFileAttributes
import collection.JavaConversions._
import com.typesafe.scalalogging.LazyLogging

class WatchServiceTask(notifyActor: ActorRef) extends Runnable with LazyLogging {
  private val watchService = FileSystems.getDefault.newWatchService()

  def watchRecursively(root: Path) {
    watch(root)
    Files.walkFileTree(root, new SimpleFileVisitor[Path] {
      override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes) = {
        watch(dir)
        FileVisitResult.CONTINUE
      }
    })
  }

  private def watch(path: Path) =
    path.register(watchService, ENTRY_CREATE, ENTRY_DELETE)

  def run() {
    import FileSystemChange._
    
    try {
      logger.debug("Waiting for file system events...")
      while (!Thread.currentThread().isInterrupted) {
        val key = watchService.take()
        key.pollEvents() foreach {
          event =>
            val relativePath = event.context().asInstanceOf[Path]
            val path = key.watchable().asInstanceOf[Path].resolve(relativePath)
            event.kind() match {
              case ENTRY_CREATE =>
                if (path.toFile.isDirectory) {
                  watchRecursively(path)
                }
                notifyActor ! Created(path.toFile)
              case ENTRY_DELETE =>
                notifyActor ! Deleted(path.toFile)
              case x =>
                logger.warn(s"Unknown event $x")
            }
        }
        key.reset()
      }
    } catch {
      case e: InterruptedException =>
        logger.info("Interrupting, bye!")
    } finally {
      watchService.close()
    }
  }
}