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

package se.nimsa.sbx.scp

import java.util.concurrent.{Executor, Executors}

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.{ask, pipe}
import akka.stream.Materializer
import akka.stream.scaladsl.{Source => StreamSource}
import akka.util.Timeout
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.Contexts
import se.nimsa.sbx.dicom.streams.DicomStreamOps
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.scp.ScpProtocol._
import se.nimsa.sbx.storage.StorageService

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

class ScpActor(scpData: ScpData, storage: StorageService, executor: Executor,
               metaDataServicePath: String = "../../MetaDataService",
               anonymizationServicePath: String = "../../AnonymizationService")
              (implicit val materializer: Materializer, timeout: Timeout) extends Actor with DicomStreamOps {

  val metaDataService = context.actorSelection(metaDataServicePath)
  val anonymizationService = context.actorSelection(anonymizationServicePath)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val log = Logging(context.system, this)

  val scheduledExecutor = Executors.newSingleThreadScheduledExecutor { runnable =>
    val thread = Executors.defaultThreadFactory().newThread(runnable)
    thread.setDaemon(true)
    thread
  }

  val scp = new Scp(scpData.name, scpData.aeTitle, scpData.port, executor, scheduledExecutor, self)
  SbxLog.info("SCP", s"Started SCP ${scpData.name} with AE title ${scpData.aeTitle} on port ${scpData.port}")

  override def postStop() {
    scp.shutdown()
    scheduledExecutor.shutdown()
    SbxLog.info("SCP", s"Stopped SCP ${scpData.name}")
  }

  def receive = LoggingReceive {
    case DicomDataReceivedByScp(bytes) =>
      log.debug("SCP", s"Dicom data received using SCP ${scpData.name}")
      val streamSource = StreamSource.single(bytes)
      val source = Source(SourceType.SCP, scpData.name, scpData.id)
      val addDicomDataFuture = storeDicomData(streamSource, source, storage, Contexts.imageDataContexts)

      addDicomDataFuture.onComplete {
        case Success(metaData) =>
          system.eventStream.publish(ImageAdded(metaData.image.id, source, !metaData.imageAdded))
        case Failure(e) =>
          SbxLog.error("SCP", s"Could not add file: ${e.getMessage}")
      }

      addDicomDataFuture.pipeTo(sender)
  }

  override def callAnonymizationService[R: ClassTag](message: Any) = anonymizationService.ask(message).mapTo[R]
  override def callMetaDataService[R: ClassTag](message: Any) = metaDataService.ask(message).mapTo[R]
  override def scheduleTask(delay: FiniteDuration)(task: => Unit) = system.scheduler.scheduleOnce(delay)(task)
}

object ScpActor {
  def props(scpData: ScpData, storage: StorageService, executor: Executor)(implicit materializer: Materializer, timeout: Timeout): Props = Props(new ScpActor(scpData, storage, executor))
}
