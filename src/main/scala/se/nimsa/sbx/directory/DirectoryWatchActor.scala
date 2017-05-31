/*
 * Copyright 2017 Lars Edenbrandt
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

import java.nio.file.{FileSystems, Files, Paths}

import akka.actor.{Actor, Props}
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.stream.alpakka.file.DirectoryChange
import akka.stream.alpakka.file.scaladsl.{Directory, DirectoryChangesSource}
import akka.stream.scaladsl.{FileIO, Keep, Sink, Source => StreamSource}
import akka.stream.{ActorMaterializer, KillSwitches, UniqueKillSwitch}
import akka.util.Timeout
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.streams.StreamOps
import se.nimsa.sbx.directory.DirectoryWatchProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.storage.StorageService

import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag
import scala.util.control.NonFatal

class DirectoryWatchActor(watchedDirectory: WatchedDirectory,
                          storage: StorageService,
                          metaDataServicePath: String = "../../MetaDataService",
                          storageServicePath: String = "../../StorageService",
                          anonymizationServicePath: String = "../../AnonymizationService")
                         (implicit val timeout: Timeout) extends Actor with StreamOps {

  val storageService = context.actorSelection(storageServicePath)
  val metaDataService = context.actorSelection(metaDataServicePath)
  val anonymizationService = context.actorSelection(anonymizationServicePath)

  implicit val system = context.system
  implicit val materializer = ActorMaterializer()
  implicit val executor = context.dispatcher

  val sbxSource = Source(SourceType.DIRECTORY, watchedDirectory.name, watchedDirectory.id)

  val fs = FileSystems.getDefault

  val source =
    Directory.walk(fs.getPath(watchedDirectory.path)).map(path => (path, DirectoryChange.Creation)) // recurse on startup
      .concat(DirectoryChangesSource(fs.getPath(watchedDirectory.path), pollInterval = 5.seconds, maxBufferSize = 100000)) // watch
      .filter {
      case (_, change) => change == DirectoryChange.Creation || change == DirectoryChange.Modification
    }
      .map(_._1)
      .flatMapConcat { path =>
        if (Files.isDirectory(path))
          Directory.walk(path).filter(p => Files.isRegularFile(p)) // directory: recurse once
        else if (Files.isRegularFile(path))
          StreamSource.single(path) // regular file: pass
        else
          StreamSource.empty // other (symlinks etc): ignore
      }
      .mapAsync(5) { path => // do import
        storeData(FileIO.fromPath(path), sbxSource, storage).map { metaData =>
          system.eventStream.publish(ImageAdded(metaData.image, sbxSource, !metaData.imageAdded))
        }.recover {
          case NonFatal(e) =>
            SbxLog.error("Directory", s"Could not add file ${Paths.get(watchedDirectory.path).relativize(path)}: ${e.getMessage}")
        }
      }
      .viaMat(KillSwitches.single)(Keep.right) // add a kill switch
      .toMat(Sink.last)(Keep.left)

  var killSwitch: UniqueKillSwitch = _

  case object DicomDataProcessed

  override def preStart(): Unit = {
    killSwitch = source.run() // start watch process
  }

  override def postStop(): Unit = {
    killSwitch.shutdown() // tear down watch process
  }

  override def callAnonymizationService[R: ClassTag](message: Any) = anonymizationService.ask(message).mapTo[R]
  override def callStorageService[R: ClassTag](message: Any) = storageService.ask(message).mapTo[R]
  override def callMetaDataService[R: ClassTag](message: Any) = metaDataService.ask(message).mapTo[R]

  def receive = LoggingReceive {
    case _ =>
  }
}

object DirectoryWatchActor {
  def props(watchedDirectory: WatchedDirectory, storage: StorageService, timeout: Timeout): Props = Props(new DirectoryWatchActor(watchedDirectory, storage)(timeout))
}
