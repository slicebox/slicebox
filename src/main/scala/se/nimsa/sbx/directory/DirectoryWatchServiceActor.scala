/*
 * Copyright 2014 Lars Edenbrandt
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

package se.nimsa.sbx.directory

import java.nio.file.{Files, Paths}

import akka.actor.{Actor, PoisonPill, Props}
import akka.event.{Logging, LoggingReceive}
import akka.stream.Materializer
import akka.util.Timeout
import se.nimsa.sbx.directory.DirectoryWatchProtocol._
import se.nimsa.sbx.storage.StorageService
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.util.FutureUtil.await

class DirectoryWatchServiceActor(directoryWatchDao: DirectoryWatchDAO, storage: StorageService, deleteWatchedDirectory: Boolean)(implicit materializer: Materializer, timeout: Timeout) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  setupWatches()

  log.info("Directory watch service started")

  def receive = LoggingReceive {

    case msg: DirectoryRequest =>
      catchAndReport {

        msg match {

          case WatchDirectory(directory) =>
            watchedDirectoryForPath(directory.path) match {
              case Some(watchedDirectory) =>
                if (directory.name == watchedDirectory.name)
                  sender ! watchedDirectory
                else
                  throw new IllegalArgumentException(s"Directory watch ${watchedDirectory.name} already watches directory " + directory.path)

              case None =>

                val path = Paths.get(directory.path)

                if (!Files.isDirectory(path))
                  throw new IllegalArgumentException("Could not create directory watch: Not a directory: " + directory.path)

                getWatchedDirectories(0, 1000000).map(dir => Paths.get(dir.path)).foreach(other =>
                  if (path.startsWith(other) || other.startsWith(path))
                    throw new IllegalArgumentException("Directory intersects existing directory " + other))

                val watchedDirectory = addDirectory(directory)

                context.child(watchedDirectory.id.toString).getOrElse(
                  context.actorOf(DirectoryWatchActor.props(watchedDirectory, storage, deleteWatchedDirectory), watchedDirectory.id.toString))

                sender ! watchedDirectory
            }

          case UnWatchDirectory(watchedDirectoryId) =>
            watchedDirectoryForId(watchedDirectoryId).foreach(_ => deleteDirectory(watchedDirectoryId))
            context.child(watchedDirectoryId.toString).foreach(_ ! PoisonPill)
            sender ! DirectoryUnwatched(watchedDirectoryId)

          case GetWatchedDirectories(startIndex, count) =>
            sender ! WatchedDirectories(getWatchedDirectories(startIndex, count))

          case GetWatchedDirectoryById(id) =>
              sender ! await(directoryWatchDao.watchedDirectoryForId(id))
        }
      }

  }

  def setupWatches(): Unit = {
    val watchedDirectories = await(directoryWatchDao.listWatchedDirectories(0, 1000000))

    watchedDirectories foreach (watchedDirectory => {
      val path = Paths.get(watchedDirectory.path)
      if (Files.isDirectory(path))
        context.actorOf(DirectoryWatchActor.props(watchedDirectory, storage, deleteWatchedDirectory), watchedDirectory.id.toString)
      else
        deleteDirectory(watchedDirectory.id)
    })
  }

  def addDirectory(directory: WatchedDirectory): WatchedDirectory =
    await(directoryWatchDao.insert(directory))

  def deleteDirectory(id: Long): Unit =
    await(directoryWatchDao.deleteWatchedDirectoryWithId(id))

  def watchedDirectoryForId(watchedDirectoryId: Long): Option[WatchedDirectory] =
    await(directoryWatchDao.watchedDirectoryForId(watchedDirectoryId))

  def watchedDirectoryForPath(path: String): Option[WatchedDirectory] =
    await(directoryWatchDao.watchedDirectoryForPath(path))

  def getWatchedDirectories(startIndex: Long, count: Long): Seq[WatchedDirectory] =
    await(directoryWatchDao.listWatchedDirectories(startIndex, count))

}

object DirectoryWatchServiceActor {
  def props(directoryWatchDao: DirectoryWatchDAO, storage: StorageService, deleteWatchedDirectory: Boolean)(implicit materializer: Materializer, timeout: Timeout): Props = Props(new DirectoryWatchServiceActor(directoryWatchDao, storage, deleteWatchedDirectory))
}
