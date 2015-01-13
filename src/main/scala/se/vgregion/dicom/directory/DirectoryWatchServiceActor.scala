package se.vgregion.dicom.directory

import scala.language.postfixOps
import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import akka.actor.PoisonPill
import se.vgregion.util.PerEventCreator
import se.vgregion.dicom.DicomDispatchActor
import java.nio.file.Path
import java.nio.file.Files
import se.vgregion.util.ClientError
import se.vgregion.util.ServerError
import java.nio.file.Paths

class DirectoryWatchServiceActor(dbProps: DbProps, storage: Path) extends Actor with PerEventCreator {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DirectoryWatchDAO(dbProps.driver)

  setupDb()
  setupWatches()

  def receive = LoggingReceive {

    case msg: DirectoryRequest => msg match {

      case WatchDirectory(pathString) =>
        val path = Paths.get(pathString)
        val id = pathToId(path)
        context.child(id) match {
          case Some(actor) =>
            sender ! ClientError("Could not create directory watch: Directory already watched: " + id)
          case None =>
            try {

              if (!Files.isDirectory(path))
                sender ! ClientError("Could not create directory watch: Not a directory: " + id)

              addDirectory(path)

              context.actorOf(DirectoryWatchActor.props(path), id)

              sender ! DirectoryWatched(path)

            } catch {
              case e: Exception => sender ! ServerError("Could not create directory watch: " + e.getMessage)
            }
        }

      case UnWatchDirectory(pathString) =>
        val path = Paths.get(pathString)
        val id = pathToId(path)
        context.child(id) match {
          case Some(ref) =>
            try {

              removeDirectory(path)

              ref ! PoisonPill

              sender ! DirectoryUnwatched(path)

            } catch {
              case e: Exception => sender ! ServerError("Could not remove directory watch: " + e.getMessage)
            }
          case None =>
            sender ! ClientError("Not a watched directory: " + id)
        }

      case GetWatchedDirectories =>
        val directories = getDirectories()
        sender ! WatchedDirectories(directories)

    }

    case msg: FileAddedToWatchedDirectory =>
      perEvent(DicomDispatchActor.props(self, null, storage, dbProps), msg)

  }

  def pathToId(path: Path) = path.getFileName.toString

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def setupWatches() =
    db.withTransaction { implicit session =>
      val watchPaths = dao.list
      watchPaths foreach (path => context.actorOf(DirectoryWatchActor.props(path), pathToId(path)))
    }

  def addDirectory(path: Path) =
    db.withSession { implicit session =>
      dao.insert(path)
    }

  def removeDirectory(path: Path) =
    db.withSession { implicit session =>
      dao.remove(path)
    }

  def getDirectories() =
    db.withSession { implicit session =>
      dao.list
    }

}

object DirectoryWatchServiceActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new DirectoryWatchServiceActor(dbProps, storage))
}