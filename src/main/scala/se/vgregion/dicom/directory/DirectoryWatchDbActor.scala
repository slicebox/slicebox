package se.vgregion.dicom.directory

import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import se.vgregion.dicom.DicomDispatchProtocol._
import DirectoryWatchProtocol._
import java.nio.file.Files
import se.vgregion.app.DbProps
import akka.event.LoggingReceive

class DirectoryWatchDbActor(dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DirectoryWatchDAO(dbProps.driver)

  def receive = LoggingReceive {

    case Initialize =>
      db.withSession { implicit session =>
        dao.create
      }
      sender ! Initialized

    case AddDirectory(path) =>
      if (Files.isDirectory(path))
        db.withTransaction { implicit session =>
          val watchedDirectories = dao.list
          if (watchedDirectories.contains(path))
            sender ! DirectoryAlreadyAdded(path)
          else {
            dao.insert(path)
            sender ! DirectoryAdded(path)
          }
        }
      else
        sender ! NotADirectory(path)

    case RemoveDirectory(path) =>
      if (Files.isDirectory(path))
        db.withSession { implicit session =>
          val nRemoved = dao.remove(path)
          if (nRemoved == 0)
            sender ! WatchedDirectoryNotFound(path)
          else
            sender ! DirectoryRemoved(path)
        }
      else
        sender ! NotADirectory(path)

    case GetWatchedDirectories =>
      db.withSession { implicit session =>
        sender ! WatchedDirectories(dao.list)
      }

  }

}

object DirectoryWatchDbActor {
  def props(dbProps: DbProps): Props = Props(new DirectoryWatchDbActor(dbProps))
}
