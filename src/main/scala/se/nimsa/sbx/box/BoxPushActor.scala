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

package se.nimsa.sbx.box

import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import akka.pattern.ask
import BoxProtocol._
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import spray.client.pipelining._
import org.dcm4che3.data.Attributes
import spray.http.HttpData
import java.nio.file.Path
import spray.http.HttpRequest
import spray.http.StatusCodes._
import scala.concurrent.Future
import spray.http.HttpResponse
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.storage.MetaDataDAO
import se.nimsa.sbx.storage.PropertiesDAO
import spray.http.StatusCode
import se.nimsa.sbx.dicom.DicomUtil._
import java.io.ByteArrayOutputStream
import java.util.Date
import akka.actor.ReceiveTimeout
import se.nimsa.sbx.log.SbxLog
import akka.util.Timeout
import se.nimsa.sbx.storage.StorageProtocol.GetDataset
import se.nimsa.sbx.app.GeneralProtocol._

class BoxPushActor(box: Box,
                   dbProps: DbProps,
                   storage: Path,
                   implicit val timeout: Timeout,
                   pollInterval: FiniteDuration = 5.seconds,
                   receiveTimeout: FiniteDuration = 1.minute,
                   storageServicePath: String = "../../StorageService",
                   anonymizationServicePath: String = "../../AnonymizationService") extends Actor {

  val log = Logging(context.system, this)

  val db = dbProps.db
  val boxDao = new BoxDAO(dbProps.driver)

  val storageService = context.actorSelection(storageServicePath)
  val anonymizationService = context.actorSelection(anonymizationServicePath)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  def sendFilePipeline = sendReceive

  def pushImagePipeline(outboxEntry: OutboxEntry, tagValues: Seq[TransactionTagValue]): Future[HttpResponse] = {
    val futureDatasetMaybe = storageService.ask(GetDataset(outboxEntry.imageId)).mapTo[Option[Attributes]]
    futureDatasetMaybe.flatMap(_ match {
      case Some(dataset) =>
        val futureAnonymizedDataset = anonymizationService.ask(Anonymize(dataset, tagValues.map(_.tagValue))).mapTo[Attributes]
        futureAnonymizedDataset flatMap { anonymizedDataset =>
          val bytes = toByteArray(anonymizedDataset)
          sendFilePipeline(Post(s"${box.baseUrl}/image?transactionid=${outboxEntry.transactionId}&sequencenumber=${outboxEntry.sequenceNumber}&totalimagecount=${outboxEntry.totalImageCount}", HttpData(bytes)))
        }
      case None =>
        Future.failed(new IllegalArgumentException("No dataset found for image id " + outboxEntry.imageId))
    })
  }

  val poller = system.scheduler.schedule(pollInterval, pollInterval) {
    self ! PollOutbox
  }

  override def postStop() =
    poller.cancel()

  context.setReceiveTimeout(receiveTimeout)

  def receive = LoggingReceive {
    case PollOutbox =>
      processNextOutboxEntry
  }

  def processNextOutboxEntry(): Unit =
    nextOutboxEntry match {
      case Some(entry) =>
        context.become(waitForFileSentState)
        sendFileForOutboxEntry(entry)

      case None =>
        context.unbecome
    }

  def waitForFileSentState: Receive = LoggingReceive {

    case FileSent(outboxEntry) =>
      handleFileSentForOutboxEntry(outboxEntry)

    case FileSendFailed(outboxEntry, statusCode, exception) =>
      handleFileSendFailedForOutboxEntry(outboxEntry, statusCode, exception)

    case ReceiveTimeout =>
      log.error("Processing next outbox entry timed out")
      context.unbecome
  }

  def nextOutboxEntry: Option[OutboxEntry] =
    db.withSession { implicit session =>
      boxDao.nextOutboxEntryForRemoteBoxId(box.id)
    }

  def sendFileForOutboxEntry(outboxEntry: OutboxEntry) = {
    val transactionTagValues = tagValuesForImageIdAndTransactionId(outboxEntry.imageId, outboxEntry.transactionId)
    sendFile(outboxEntry, transactionTagValues)
  }

  def tagValuesForImageIdAndTransactionId(imageId: Long, transactionId: Long): Seq[TransactionTagValue] =
    db.withSession { implicit session =>
      boxDao.tagValuesByImageIdAndTransactionId(imageId, transactionId)
    }

  def sendFile(outboxEntry: OutboxEntry, tagValues: Seq[TransactionTagValue]) = {
    pushImagePipeline(outboxEntry, tagValues)
      .map(response => {
        val statusCode = response.status.intValue
        if (statusCode >= 200 && statusCode < 300)
          self ! FileSent(outboxEntry)
        else {
          val errorMessage = response.entity.asString
          self ! FileSendFailed(outboxEntry, statusCode, new Exception(s"File send failed with status code $statusCode: $errorMessage"))
        }
      })
      .recover {
        case exception: IllegalArgumentException =>
          self ! FileSendFailed(outboxEntry, 400, exception)
        case exception: Exception =>
          self ! FileSendFailed(outboxEntry, 500, exception)
      }
  }

  def handleFileSentForOutboxEntry(outboxEntry: OutboxEntry) = {
    log.debug(s"File sent for outbox entry ${outboxEntry.id}")

    db.withSession { implicit session =>
      boxDao.removeOutboxEntry(outboxEntry.id)
      val sentEntry = boxDao.updateSent(outboxEntry.remoteBoxId, outboxEntry.remoteBoxName, outboxEntry.transactionId, outboxEntry.sequenceNumber, outboxEntry.totalImageCount)
      boxDao.insertSentImage(SentImage(-1, sentEntry.id, outboxEntry.imageId))
    }

    if (outboxEntry.sequenceNumber == outboxEntry.totalImageCount) {
      context.system.eventStream.publish(ImagesSent(Destination(DestinationType.BOX, box.name, box.id), sentImageIdsForTransactionId(outboxEntry.transactionId)))
      SbxLog.info("Box", s"Finished sending ${outboxEntry.totalImageCount} images to box ${box.name}")
      removeTransactionTagValuesForTransactionId(outboxEntry.transactionId)
    }

    processNextOutboxEntry
  }

  def handleFileSendFailedForOutboxEntry(outboxEntry: OutboxEntry, statusCode: Int, exception: Exception) = {
    log.debug(s"Failed to send file to box ${box.name}: ${exception.getMessage}")
    statusCode match {
      case code if code >= 500 =>
      // server-side error, remote box is most likely down
      case _ =>
        markOutboxTransactionAsFailed(outboxEntry, s"Cannot send file to box ${box.name}: ${exception.getMessage}")
    }
    context.unbecome
  }

  def markOutboxTransactionAsFailed(outboxEntry: OutboxEntry, logMessage: String) = {
    db.withSession { implicit session =>
      boxDao.markOutboxTransactionAsFailed(box.id, outboxEntry.transactionId)
    }
    SbxLog.error("Box", logMessage)
  }

  def removeTransactionTagValuesForTransactionId(transactionId: Long) =
    db.withSession { implicit session =>
      boxDao.removeTransactionTagValuesByTransactionId(transactionId)
    }

  def sentImageIdsForTransactionId(transactionId: Long): Seq[Long] =
    db.withSession { implicit session =>
      boxDao.sentImagesByTransactionId(transactionId).map(_.imageId)
    }

}

object BoxPushActor {

  def props(box: Box,
            dbProps: DbProps,
            storage: Path,
            timeout: Timeout): Props =
    Props(new BoxPushActor(box, dbProps, storage, timeout))

}
