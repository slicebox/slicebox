/*
 * Copyright 2015 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.dicom.directory

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
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.dicom.DicomDispatchActor
import se.nimsa.sbx.dicom.DicomProtocol._
import akka.actor.Status.Failure
import se.nimsa.sbx.util.ExceptionCatching

class DirectoryWatchServiceActor(dbProps: DbProps, storage: Path) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DirectoryWatchDAO(dbProps.driver)

  setupDb()
  setupWatches()

  log.info("Directory watch service started")    
      
  def receive = LoggingReceive {

    case msg: DirectoryRequest =>
      catchAndReport {

        msg match {

          case WatchDirectory(pathString) =>
            watchedDirectoryForPath(pathString) match {
              case Some(watchedDirectory) =>

                sender ! watchedDirectory

              case None =>

                val path = Paths.get(pathString)

                if (!Files.isDirectory(path))
                  throw new IllegalArgumentException("Could not create directory watch: Not a directory: " + pathString)

                if (Files.isSameFile(path, storage))
                  throw new IllegalArgumentException("The storage directory may not be watched.")

                getWatchedDirectories.map(dir => Paths.get(dir.path)).foreach(other =>
                  if (path.startsWith(other) || other.startsWith(path))
                    throw new IllegalArgumentException("Directory intersects existing directory " + other))

                val watchedDirectory = addDirectory(pathString)

                context.child(watchedDirectory.id.toString).getOrElse(
                  context.actorOf(DirectoryWatchActor.props(pathString), watchedDirectory.id.toString))

                sender ! watchedDirectory
            }

          case UnWatchDirectory(watchedDirectoryId) =>
            watchedDirectoryForId(watchedDirectoryId).foreach(dir => deleteDirectory(watchedDirectoryId))
            context.child(watchedDirectoryId.toString).foreach(_ ! PoisonPill)
            sender ! DirectoryUnwatched(watchedDirectoryId)

          case GetWatchedDirectories =>
            val directories = getWatchedDirectories()
            sender ! WatchedDirectories(directories)

        }
      }

  }

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
          context.actorOf(DirectoryWatchActor.props(watchedDirectory.path), watchedDirectory.id.toString)
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

  def watchedDirectoryForPath(path: String) =
    db.withSession { implicit session =>
      dao.watchedDirectoryForPath(path)
    }

  def getWatchedDirectories() =
    db.withSession { implicit session =>
      dao.allWatchedDirectories
    }

}

object DirectoryWatchServiceActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new DirectoryWatchServiceActor(dbProps, storage))
}
