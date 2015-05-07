/*
 * Copyright 2015 Karl Sjöstrand
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
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.dicom.DicomMetaDataDAO
import spray.http.StatusCode
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.DicomAnonymization._
import java.io.ByteArrayOutputStream
import java.util.Date
import akka.actor.ReceiveTimeout
import se.nimsa.sbx.log.SbxLog

class BoxPushActor(box: Box,
                   dbProps: DbProps,
                   storage: Path,
                   pollInterval: FiniteDuration = 5.seconds,
                   receiveTimeout: FiniteDuration = 1.minute) extends Actor {

  val log = Logging(context.system, this)

  val db = dbProps.db
  val boxDao = new BoxDAO(dbProps.driver)
  val dicomMetaDataDao = new DicomMetaDataDAO(dbProps.driver)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  def sendFilePipeline = sendReceive

  def pushImagePipeline(outboxEntry: OutboxEntry, fileName: String, tagValues: Seq[TransactionTagValue]): Future[HttpResponse] = {
    val path = storage.resolve(fileName)
    val dataset = loadDataset(path, true)
    val anonymizedDataset = anonymizeDataset(dataset)
    applyTagValues(anonymizedDataset, tagValues)
    val bytes = toByteArray(anonymizedDataset)
    sendFilePipeline(Post(s"${box.baseUrl}/image?transactionid=${outboxEntry.transactionId}&sequencenumber=${outboxEntry.sequenceNumber}&totalimagecount=${outboxEntry.totalImageCount}", HttpData(bytes)))
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

  def sendFileForOutboxEntry(outboxEntry: OutboxEntry) =
    fileNameForImageFileId(outboxEntry.imageFileId) match {
      case Some(fileName) =>
        val transactionTagValues = tagValuesForImageFileIdAndTransactionId(outboxEntry.imageFileId, outboxEntry.transactionId)
        sendFileWithName(outboxEntry, fileName, transactionTagValues)
      case None =>
        handleFilenameLookupFailedForOutboxEntry(outboxEntry, new IllegalStateException(s"Can't process outbox entry (${outboxEntry.id}) because no image with id ${outboxEntry.imageFileId} was found"))
    }

  def fileNameForImageFileId(imageFileId: Long): Option[String] =
    db.withSession { implicit session =>
      dicomMetaDataDao.imageFileById(imageFileId).map(_.fileName.value)
    }

  def tagValuesForImageFileIdAndTransactionId(imageFileId: Long, transactionId: Long): Seq[TransactionTagValue] =
    db.withSession { implicit session =>
      boxDao.tagValuesByImageFileIdAndTransactionId(imageFileId, transactionId)
    }

  def sendFileWithName(outboxEntry: OutboxEntry, fileName: String, tagValues: Seq[TransactionTagValue]) = {
    pushImagePipeline(outboxEntry, fileName, tagValues)
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
        case exception: Exception =>
          self ! FileSendFailed(outboxEntry, 500, exception)
      }
  }

  def handleFileSentForOutboxEntry(outboxEntry: OutboxEntry) = {
    log.debug(s"File sent for outbox entry ${outboxEntry.id}")

    db.withSession { implicit session =>
      boxDao.removeOutboxEntry(outboxEntry.id)
    }

    if (outboxEntry.sequenceNumber == outboxEntry.totalImageCount) {
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

  def handleFilenameLookupFailedForOutboxEntry(outboxEntry: OutboxEntry, exception: Exception) = {    
    markOutboxTransactionAsFailed(outboxEntry, s"Failed to send file to box ${box.name}: " + exception.getMessage)
    context.unbecome
  }

  def markOutboxTransactionAsFailed(outboxEntry: OutboxEntry, logMessage: String) = {
    db.withSession { implicit session =>
      boxDao.markOutboxTransactionAsFailed(box.id, outboxEntry.transactionId)
    }
    SbxLog.error("Box", logMessage)
  }

  def removeTransactionTagValuesForTransactionId(transactionId: Long) = {
    db.withSession { implicit session =>
      boxDao.removeTransactionTagValuesByTransactionId(transactionId)
    }
  }

}

object BoxPushActor {

  def props(box: Box,
            dbProps: DbProps,
            storage: Path,
            pollInterval: FiniteDuration = 5.seconds,
            receiveTimeout: FiniteDuration = 1.minute): Props =
    Props(new BoxPushActor(box, dbProps, storage, pollInterval, receiveTimeout))

}
