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

package se.nimsa.sbx.scp

import java.util.concurrent.{Executor, Executors}

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.{ask, pipe}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.anonymization.AnonymizationProtocol.ReverseAnonymization
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.DicomData
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.streams.StreamOps
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, MetaDataAdded}
import se.nimsa.sbx.scp.ScpProtocol._
import se.nimsa.sbx.storage.StorageProtocol.{AddDicomData, CheckDicomData, DicomDataAdded}
import se.nimsa.sbx.storage.StorageService

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

class ScpActor(scpData: ScpData, storage: StorageService, executor: Executor,
               metaDataServicePath: String = "../../MetaDataService",
               storageServicePath: String = "../../StorageService",
               anonymizationServicePath: String = "../../AnonymizationService")
              (implicit val timeout: Timeout) extends Actor with StreamOps {

  val metaDataService = context.actorSelection(metaDataServicePath)
  val storageService = context.actorSelection(storageServicePath)
  val anonymizationService = context.actorSelection(anonymizationServicePath)

  implicit val system = context.system
  implicit val materializer= ActorMaterializer()
  implicit val ec = context.dispatcher

  val log = Logging(context.system, this)

  val scheduledExecutor = Executors.newSingleThreadScheduledExecutor { runnable =>
    val thread = Executors.defaultThreadFactory().newThread(runnable)
    thread.setDaemon(true)
    thread
  }

  val scp = new Scp(scpData.name, scpData.aeTitle, scpData.port, executor, scheduledExecutor, self, timeout)
  SbxLog.info("SCP", s"Started SCP ${scpData.name} with AE title ${scpData.aeTitle} on port ${scpData.port}")

  override def postStop() {
    scp.shutdown()
    scheduledExecutor.shutdown()
    SbxLog.info("SCP", s"Stopped SCP ${scpData.name}")
  }

  def receive = LoggingReceive {
    case DicomDataReceivedByScp(bytesSource) =>
      log.debug("SCP", s"Dicom data received using SCP ${scpData.name}")
      val source = Source(SourceType.SCP, scpData.name, scpData.id)
      val addDicomDataFuture = storeData(bytesSource, source, storage)

      addDicomDataFuture.onComplete {
        case Success(metaData) =>
          system.eventStream.publish(ImageAdded(metaData.image, source, !metaData.imageAdded))
        case Failure(e) =>
          SbxLog.error("Directory", s"Could not add file: ${e.getMessage}")
      }

      addDicomDataFuture.pipeTo(sender)
  }

  def addMetadata(attributes: Attributes, source: Source): Future[Image] =
    metaDataService.ask(
      AddMetaData(attributes, source))
      .mapTo[MetaDataAdded]
      .map(_.image)

  def addDicomData(dicomData: DicomData, source: Source, image: Image): Future[Boolean] =
    storageService.ask(AddDicomData(dicomData, source, image))
      .mapTo[DicomDataAdded]
      .map(_.overwrite)

  def checkDicomData(dicomData: DicomData): Future[Boolean] =
    storageService.ask(CheckDicomData(dicomData, useExtendedContexts = false)).mapTo[Boolean]

  def reverseAnonymization(attributes: Attributes): Future[Attributes] =
    anonymizationService.ask(ReverseAnonymization(attributes)).mapTo[Attributes]

  override def callAnonymizationService[R: ClassTag](message: Any) = anonymizationService.ask(message).mapTo[R]
  override def callStorageService[R: ClassTag](message: Any) = storageService.ask(message).mapTo[R]
  override def callMetaDataService[R: ClassTag](message: Any) = metaDataService.ask(message).mapTo[R]
}

object ScpActor {
  def props(scpData: ScpData, storage: StorageService, executor: Executor, timeout: Timeout): Props = Props(new ScpActor(scpData, storage, executor)(timeout))
}
