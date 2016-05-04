/*
 * Copyright 2016 Lars Edenbrandt
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

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.ask
import akka.util.Timeout
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.directory.DirectoryWatchProtocol._
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, MetaDataAdded}
import se.nimsa.sbx.storage.StorageProtocol.{AddDataset, CheckDataset, DatasetAdded}

import scala.concurrent.Future
import scala.util.control.NonFatal

class DirectoryWatchActor(watchedDirectory: WatchedDirectory,
                          implicit val timeout: Timeout,
                          metaDataServicePath: String = "../../MetaDataService",
                          storageServicePath: String = "../../StorageService") extends Actor {

  val log = Logging(context.system, this)

  val watchServiceTask = new DirectoryWatch(self)

  val watchThread = new Thread(watchServiceTask, "WatchService")

  val storageService = context.actorSelection(storageServicePath)
  val metaDataService = context.actorSelection(metaDataServicePath)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  override def preStart() {
    watchThread.setDaemon(true)
    watchThread.start()
    watchServiceTask watchRecursively Paths.get(watchedDirectory.path)
  }

  override def postStop() {
    watchThread.interrupt()
  }

  def receive = LoggingReceive {

    case FileAddedToWatchedDirectory(path) =>
      if (Files.isRegularFile(path)) {
        val dataset = loadDataset(path, withPixelData = true, useBulkDataURI = false)
        val source = Source(SourceType.DIRECTORY, watchedDirectory.name, watchedDirectory.id)
        checkDataset(dataset).flatMap { status =>
          addMetadata(dataset, source).flatMap { image =>
            addDataset(dataset, source, image).map { overwrite =>
            }
          }
        }.onFailure {
          case NonFatal(e) => SbxLog.error("Directory", s"Could not add file: ${e.getMessage}")
        }
      }

  }

  def addMetadata(dataset: Attributes, source: Source): Future[Image] =
    metaDataService.ask(
      AddMetaData(dataset, source))
      .mapTo[MetaDataAdded]
      .map(_.image)

  def addDataset(dataset: Attributes, source: Source, image: Image): Future[Boolean] =
    storageService.ask(AddDataset(dataset, source, image))
      .mapTo[DatasetAdded]
      .map(_.overwrite)

  def checkDataset(dataset: Attributes): Future[Boolean] =
    storageService.ask(CheckDataset(dataset, restrictSopClass = true)).mapTo[Boolean]

}

object DirectoryWatchActor {
  def props(watchedDirectory: WatchedDirectory, timeout: Timeout): Props = Props(new DirectoryWatchActor(watchedDirectory, timeout))
}
