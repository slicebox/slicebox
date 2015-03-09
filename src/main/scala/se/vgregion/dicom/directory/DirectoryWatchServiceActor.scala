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
import se.vgregion.util.ExceptionCatching

class DirectoryWatchServiceActor(dbProps: DbProps, storage: Path) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DirectoryWatchDAO(dbProps.driver)

  setupDb()
  setupWatches()

  def receive = LoggingReceive {

    case msg: DirectoryRequest =>
      catchAndReport {

        msg match {

          case WatchDirectory(pathString) =>
            val path = Paths.get(pathString)
            val childActorId = pathToId(path)
            context.child(childActorId) match {
              case Some(actor) =>

                sender ! DirectoryWatched(path)

              case None =>

                if (!Files.isDirectory(path))
                  throw new IllegalArgumentException("Could not create directory watch: Not a directory: " + pathString)

                if (Files.isSameFile(path, storage))
                  throw new IllegalArgumentException("The storage directory may not be watched.")

                getWatchedDirectories.map(dir => Paths.get(dir.path)).foreach(other =>
                  if (path.startsWith(other) || other.startsWith(path))
                    throw new IllegalArgumentException("Directory intersects existing directory " + other))

                addDirectory(pathString)

                context.actorOf(DirectoryWatchActor.props(pathString), childActorId)

                sender ! DirectoryWatched(path)
            }

          case UnWatchDirectory(watchedDirectoryId) =>
            watchedDirectoryForId(watchedDirectoryId) match {
              case Some(watchedDirectory) =>
                deleteDirectory(watchedDirectoryId)

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
      watchedDirectories foreach (watchedDirectory => {
        val path = Paths.get(watchedDirectory.path)
        if (Files.isDirectory(path))
          context.actorOf(DirectoryWatchActor.props(watchedDirectory.path), pathToId(Paths.get(watchedDirectory.path)))
        else
          deleteDirectory(watchedDirectory.id)
      })
    }

  def addDirectory(pathString: String): WatchedDirectory =
    db.withSession { implicit session =>
      dao.insert(WatchedDirectory(-1, pathString))
    }

  def deleteDirectory(id: Long) =
    db.withSession { implicit session =>
      dao.deleteWatchedDirectoryWithId(id)
    }

  def watchedDirectoryForId(watchedDirectoryId: Long) =
    db.withSession { implicit session =>
      dao.watchedDirectoryForId(watchedDirectoryId)
    }

  def getWatchedDirectories() =
    db.withSession { implicit session =>
      dao.allWatchedDirectories
    }

}

object DirectoryWatchServiceActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new DirectoryWatchServiceActor(dbProps, storage))
}