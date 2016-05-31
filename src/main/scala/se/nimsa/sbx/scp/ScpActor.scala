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

package se.nimsa.sbx.scp

import java.util.concurrent.{Executor, Executors, ThreadFactory}

import akka.actor.{Actor, Props, Stash}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.ask
import akka.util.Timeout
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.anonymization.AnonymizationProtocol.ReverseAnonymization
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, MetaDataAdded}
import se.nimsa.sbx.scp.ScpProtocol._
import se.nimsa.sbx.storage.StorageProtocol.{AddDataset, CheckDataset, DatasetAdded}

import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

class ScpActor(scpData: ScpData, executor: Executor, implicit val timeout: Timeout,
               metaDataServicePath: String = "../../MetaDataService",
               storageServicePath: String = "../../StorageService",
               anonymizationServicePath: String = "../../AnonymizationService") extends Actor with Stash {

  val metaDataService = context.actorSelection(metaDataServicePath)
  val storageService = context.actorSelection(storageServicePath)
  val anonymizationService = context.actorSelection(anonymizationServicePath)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val log = Logging(context.system, this)

  val scheduledExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
    override def newThread(runnable: Runnable): Thread = {
      val thread = Executors.defaultThreadFactory().newThread(runnable)
      thread.setDaemon(true)
      thread
    }
  })

  val scp = new Scp(scpData.name, scpData.aeTitle, scpData.port, self)
  scp.device.setScheduledExecutor(scheduledExecutor)
  scp.device.setExecutor(executor)
  scp.device.bindConnections()
  SbxLog.info("SCP", s"Started SCP ${scpData.name} with AE title ${scpData.aeTitle} on port ${scpData.port}")

  override def postStop() {
    scp.device.unbindConnections()
    scheduledExecutor.shutdown()
    SbxLog.info("SCP", s"Stopped SCP ${scpData.name}")
  }

  case object DatasetProcessed

  def receive = LoggingReceive {
    case DatasetReceivedByScp(dataset) =>
      log.debug("SCP", s"Dataset received using SCP ${scpData.name}")
      val source = Source(SourceType.SCP, scpData.name, scpData.id)
      context.become(waitForDatasetProcessed)
      checkDataset(dataset).flatMap { status =>
        reverseAnonymization(dataset).flatMap { reversedDataset =>
          addMetadata(reversedDataset, source).flatMap { image =>
            addDataset(reversedDataset, source, image).map { overwrite =>
            }
          }
        }
      }.onComplete {
        case Success(_) =>
          self ! DatasetProcessed
        case Failure(NonFatal(e)) =>
          SbxLog.error("Directory", s"Could not add file: ${e.getMessage}")
          self ! DatasetProcessed
      }
  }

  def waitForDatasetProcessed = LoggingReceive {
    case msg: DatasetReceivedByScp =>
      stash()
    case DatasetProcessed =>
      context.unbecome()
      unstashAll()
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

  def reverseAnonymization(dataset: Attributes): Future[Attributes] =
    anonymizationService.ask(ReverseAnonymization(dataset)).mapTo[Attributes]

}

object ScpActor {
  def props(scpData: ScpData, executor: Executor, timeout: Timeout): Props = Props(new ScpActor(scpData, executor, timeout))
}
