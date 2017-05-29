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

import java.nio.file.{Files, Paths}

import akka.actor.{Actor, Props, Stash}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.FileIO
import akka.util.Timeout
import org.dcm4che3.data.Attributes
import org.dcm4che3.io.DicomStreamException
import se.nimsa.sbx.anonymization.AnonymizationServiceCalls
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.streams.DicomStreams
import se.nimsa.sbx.dicom.streams.DicomStreams.dicomDataSink
import se.nimsa.sbx.directory.DirectoryWatchProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, MetaDataAdded}
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.storage.StorageService

import scala.reflect.ClassTag
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class DirectoryWatchActor(watchedDirectory: WatchedDirectory,
                          storage: StorageService,
                          metaDataServicePath: String = "../../MetaDataService",
                          storageServicePath: String = "../../StorageService",
                          anonymizationServicePath: String = "../../AnonymizationService")
                         (implicit val timeout: Timeout) extends Actor with Stash with AnonymizationServiceCalls {

  val log = Logging(context.system, this)

  val watchServiceTask = new DirectoryWatch(self)

  val watchThread = new Thread(watchServiceTask, "WatchService")

  val storageService = context.actorSelection(storageServicePath)
  val metaDataService = context.actorSelection(metaDataServicePath)
  val anonymizationService = context.actorSelection(anonymizationServicePath)

  implicit val system = context.system
  implicit val materializer = ActorMaterializer()
  implicit val executor = context.dispatcher

  case object DicomDataProcessed

  override def preStart() {
    watchThread.setDaemon(true)
    watchThread.start()
    watchServiceTask watchRecursively Paths.get(watchedDirectory.path)
  }

  override def postStop() {
    watchThread.interrupt()
  }

  override def callAnonymizationService[R: ClassTag](message: Any) = anonymizationService.ask(message).mapTo[R]

  def receive = LoggingReceive {

    case FileAddedToWatchedDirectory(path) =>
      if (Files.isRegularFile(path)) {
        val source = Source(SourceType.DIRECTORY, watchedDirectory.name, watchedDirectory.id)
        val tempPath = DicomStreams.createTempPath()
        val futureImport = FileIO.fromPath(path).runWith(dicomDataSink(storage.fileSink(tempPath), reverseAnonymizationQuery))

        context.become(waitForDatasetProcessed)

        futureImport.flatMap {
          case (_, maybeDataset) =>
            val attributes: Attributes = maybeDataset.getOrElse(throw new DicomStreamException("DICOM data has no dataset"))
            metaDataService.ask(AddMetaData(attributes, source)).mapTo[MetaDataAdded].flatMap { metaData =>
              storageService.ask(MoveDicomData(tempPath, s"${metaData.image.id}"))
            }
        }.onComplete {
          case Success(_) =>
            self ! DicomDataProcessed
          case Failure(NonFatal(e)) =>
            SbxLog.error("Directory", s"Could not add file ${Paths.get(watchedDirectory.path).relativize(path)}: ${e.getMessage}")
            self ! DicomDataProcessed
        }
      }

  }

  def waitForDatasetProcessed: Receive = LoggingReceive {
    case _: FileAddedToWatchedDirectory =>
      stash()
    case DicomDataProcessed =>
      context.unbecome()
      unstashAll()
  }

}

object DirectoryWatchActor {
  def props(watchedDirectory: WatchedDirectory, storage: StorageService, timeout: Timeout): Props = Props(new DirectoryWatchActor(watchedDirectory, storage)(timeout))
}
