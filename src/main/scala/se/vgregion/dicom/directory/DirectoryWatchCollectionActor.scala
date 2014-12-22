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

class DirectoryWatchCollectionActor(dbProps: DbProps, storage: Path) extends Actor with PerEventCreator {
  val log = Logging(context.system, this)

  def receive = LoggingReceive {

    case WatchDirectoryPath(path) =>
      try {
        context.actorOf(DirectoryWatchActor.props(path), path.getFileName.toString)
        sender ! DirectoryWatched(path)
      } catch {
        case e: Exception => sender ! DirectoryWatchFailed("Could not create directory watch: " + e.getMessage)
      }
    case UnWatchDirectoryPath(path) =>
      context.child(path.getFileName.toString) match {
        case Some(ref) => 
          ref ! PoisonPill
          sender ! DirectoryUnwatched(path)
        case None =>
          sender ! DirectoryWatchFailed("Could not stop watching directory " + path)
      }
    case msg: FileAddedToWatchedDirectory =>
      perEvent(DicomDispatchActor.props(self, null, storage, dbProps), msg)

  }
}

object DirectoryWatchCollectionActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new DirectoryWatchCollectionActor(dbProps, storage))
}