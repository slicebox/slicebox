package se.vgregion.dicom.directory

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.language.postfixOps
import akka.actor.Actor
import akka.actor.PoisonPill
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomDispatchActor
import se.vgregion.dicom.DicomProtocol._
import akka.actor.Status.Failure

class DirectoryWatchServiceActor(dbProps: DbProps, storage: Path) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DirectoryWatchDAO(dbProps.driver)

  setupDb()
  setupWatches()

  def receive = LoggingReceive {

    case msg: DirectoryRequest => msg match {

      case WatchDirectory(pathString) =>
        val path = Paths.get(pathString)
        val childActorId = pathToId(path)
        context.child(childActorId) match {
          case Some(actor) =>
            
            sender ! DirectoryWatched(path)
         
          case None =>
            
            if (!Files.isDirectory(path)) {

              sender ! Failure(new IllegalArgumentException("Could not create directory watch: Not a directory: " + pathString))

            } else if (Files.isSameFile(path, storage)) {
              
              sender ! Failure(new IllegalArgumentException("The storage directory may not be watched."))              
              
            } else {

              addDirectory(pathString)

              context.actorOf(DirectoryWatchActor.props(pathString), childActorId)

              sender ! DirectoryWatched(path)
            }
        }

      case UnWatchDirectory(watchedDirectoryId) =>
        db.withSession { implicit session =>
          dao.watchedDirectoryForId(watchedDirectoryId)
        } match {
          case Some(watchedDirectory) =>
            db.withSession { implicit session =>
              dao.deleteWatchedDirectoryWithId(watchedDirectoryId)
            }
            
            val path = Paths.get(watchedDirectory.path)

            val id = pathToId(path)
            context.child(id) match {
              case Some(ref) =>
                ref ! PoisonPill

                sender ! DirectoryUnwatched(watchedDirectoryId)
              case None =>
                sender ! DirectoryUnwatched(watchedDirectoryId)
            }

          case None =>
            sender ! DirectoryUnwatched(watchedDirectoryId)
        }
        
      case GetWatchedDirectories =>
        val directories = getWatchedDirectories()
        sender ! WatchedDirectories(directories)

    }

  }

  def pathToId(path: Path) =
    path.toAbsolutePath().toString
      .replace(" ", "_") // Spaces are not allowed in actor ids
      .replace("-", "--") // Replace '-' in directory names to avoid conflict with directory separator in next replace below
      .replace(FileSystems.getDefault.getSeparator, "-")

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def setupWatches() =
    db.withTransaction { implicit session =>
      val watchedDirectories = dao.allWatchedDirectories
      watchedDirectories foreach (watchedDirectory => context.actorOf(DirectoryWatchActor.props(watchedDirectory.path), pathToId(Paths.get(watchedDirectory.path))))
    }

  def addDirectory(pathString: String): WatchedDirectory =
    db.withSession { implicit session =>
      dao.insert(WatchedDirectory(-1, pathString))
    }

  def getWatchedDirectories() =
    db.withSession { implicit session =>
      dao.allWatchedDirectories
    }

}

object DirectoryWatchServiceActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new DirectoryWatchServiceActor(dbProps, storage))
}