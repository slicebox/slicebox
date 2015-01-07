package se.vgregion.dicom.directory

import scala.language.postfixOps
import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomDispatchProtocol._
import DirectoryWatchProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import akka.actor.PoisonPill
import se.vgregion.util.PerEventCreator
import se.vgregion.dicom.DicomDispatchActor
import java.nio.file.Path
import java.nio.file.Files

class DirectoryWatchCollectionActor(dbProps: DbProps, storage: Path) extends Actor with PerEventCreator {
  val log = Logging(context.system, this)
  
  val db = dbProps.db
  val dao = new DirectoryWatchDAO(dbProps.driver)
  
  setupDb()

  def receive = LoggingReceive {

    case WatchDirectoryPath(path) =>
      try {
        addDirectory(path)
        context.actorOf(DirectoryWatchActor.props(path), path.getFileName.toString)
        sender ! DirectoryWatched(path)
      } catch {
        case e: Exception => sender ! DirectoryWatchFailed("Could not create directory watch: " + e.getMessage)
      }
      
    case UnWatchDirectoryPath(path) =>
      context.child(path.getFileName.toString) match {
        case Some(ref) => 
          try {
            removeDirectory(path)
            ref ! PoisonPill
            sender ! DirectoryUnwatched(path)
          } catch {
            case e: Exception => sender ! DirectoryUnWatchFailed("Could not remove directory watch: " + e.getMessage)
          }
        case None =>
          sender ! DirectoryWatchFailed("Could not stop watching directory " + path)
      }
      
    case GetWatchedDirectories =>
      db.withSession { implicit session =>
        sender ! WatchedDirectories(dao.list)
      }
      
    case msg: FileAddedToWatchedDirectory =>
      perEvent(DicomDispatchActor.props(self, null, storage, dbProps), msg)

  }
  
  def setupDb() {
    db.withSession { implicit session =>
      dao.create
    }
  }
  
  def addDirectory(path: Path) {
    if (!Files.isDirectory(path)) {
      throw new IllegalArgumentException(s"$path is not a directory.")
    }
      
    db.withTransaction { implicit session =>
      val watchedDirectories = dao.list
      if (!watchedDirectories.contains(path))
        dao.insert(path)
    }
  }
  
  def removeDirectory(path: Path) {
    db.withSession { implicit session =>
      dao.remove(path)
    }
  }
}

object DirectoryWatchCollectionActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new DirectoryWatchCollectionActor(dbProps, storage))
}