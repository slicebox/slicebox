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

package se.nimsa.sbx.directory

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
import DirectoryWatchProtocol.FileAddedToWatchedDirectory

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
        makeSureFileIsNotInUse(file)
        notifyActor ! FileAddedToWatchedDirectory(file)
        FileVisitResult.CONTINUE
      }
    })
  }

  private def watch(path: Path) =
    path.register(watchService, ENTRY_CREATE)

  private def makeSureFileIsNotInUse(path: Path) = {
    val file = path.toFile
    while (!file.renameTo(file)) {
      // Cannot read from file, windows still working on it.
      Thread.sleep(100)
    }
  }
  
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
                else {                  
                  makeSureFileIsNotInUse(path)
                  notifyActor ! FileAddedToWatchedDirectory(path)
                }
                
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
