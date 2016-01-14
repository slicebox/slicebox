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

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import org.dcm4che3.data.Attributes

import BoxProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.event.Logging
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout
import se.nimsa.sbx.anonymization.AnonymizationProtocol.Anonymize
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.DicomUtil.toByteArray
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.storage.StorageProtocol.GetDataset
import se.nimsa.sbx.util.CompressionUtil.compress
import spray.client.pipelining.Post
import spray.client.pipelining.sendReceive
import spray.http.HttpData
import spray.http.HttpResponse

class BoxPushActor(box: Box,
                   dbProps: DbProps,
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

  val poller = system.scheduler.schedule(pollInterval, pollInterval) {
    self ! PollOutgoing
  }

  override def postStop() =
    poller.cancel()

  context.setReceiveTimeout(receiveTimeout)

  def receive = LoggingReceive {
    case PollOutgoing =>
      processNextOutgoingEntry
  }

  def processNextOutgoingEntry(): Unit =
    nextOutgoingEntry match {
      case Some(entry) =>
        context.become(waitForFileSentState)
        sendFileForOutgoingEntry(entry)

      case None =>
        context.unbecome
    }

  def waitForFileSentState: Receive = LoggingReceive {

    case FileSent(entryImage) =>
      handleFileSentForOutgoingEntry(entryImage)

    case FileSendFailed(entryImage, statusCode, exception) =>
      handleFileSendFailedForOutgoingEntry(entryImage, statusCode, exception)

    case ReceiveTimeout =>
      log.error("Processing next outgoing entry timed out")
      context.unbecome
  }

  def nextOutgoingEntry: Option[OutgoingEntryImage] =
    db.withSession { implicit session =>
      boxDao.nextOutgoingEntryImageForRemoteBoxId(box.id)
    }

  def sendFileForOutgoingEntry(entryImage: OutgoingEntryImage) = {
    val transactionTagValues = tagValuesForImageIdAndTransactionId(entryImage.image.imageId, entryImage.entry.transactionId)
    sendFile(entryImage, transactionTagValues)
  }

  def sendFilePipeline = sendReceive

  def pushImagePipeline(entryImage: OutgoingEntryImage, tagValues: Seq[TransactionTagValue]): Future[HttpResponse] = {
    val futureDatasetMaybe = storageService.ask(GetDataset(entryImage.image.imageId, true)).mapTo[Option[Attributes]]
    futureDatasetMaybe.flatMap(_ match {
      case Some(dataset) =>
        val futureAnonymizedDataset = anonymizationService.ask(Anonymize(entryImage.image.imageId, dataset, tagValues.map(_.tagValue))).mapTo[Attributes]
        futureAnonymizedDataset flatMap { anonymizedDataset =>
          val compressedBytes = compress(toByteArray(anonymizedDataset))
          sendFilePipeline(Post(s"${box.baseUrl}/transactions/image?transactionid=${entryImage.entry.transactionId}&totalimagecount=${entryImage.entry.totalImageCount}", HttpData(compressedBytes)))
        }
      case None =>
        Future.failed(new IllegalArgumentException("No dataset found for image id " + entryImage.image.imageId))
    })
  }

  def sendFile(entryImage: OutgoingEntryImage, tagValues: Seq[TransactionTagValue]) = {
    pushImagePipeline(entryImage, tagValues)
      .map(response => {
        val statusCode = response.status.intValue
        if (statusCode >= 200 && statusCode < 300)
          self ! FileSent(entryImage)
        else {
          val errorMessage = response.entity.asString
          self ! FileSendFailed(entryImage, statusCode, new Exception(s"File send failed with status code $statusCode: $errorMessage"))
        }
      })
      .recover {
        case exception: IllegalArgumentException =>
          self ! FileSendFailed(entryImage, 400, exception)
        case exception: Exception =>
          self ! FileSendFailed(entryImage, 500, exception)
      }
  }

  def tagValuesForImageIdAndTransactionId(imageId: Long, transactionId: Long): Seq[TransactionTagValue] =
    db.withSession { implicit session =>
      boxDao.tagValuesByImageIdAndTransactionId(imageId, transactionId)
    }

  def handleFileSentForOutgoingEntry(entryImage: OutgoingEntryImage) = {
    log.debug(s"File sent for outgoing entry ${entryImage.entry.id}")

    markOutgoingImageAsSent(entryImage.image)
    val updatedEntry = updateOutgoingEntryAfterSendingFile(entryImage.entry)

    if (updatedEntry.sentImageCount == updatedEntry.totalImageCount) {
      context.system.eventStream.publish(ImagesSent(Destination(DestinationType.BOX, box.name, box.id), outgoingImageIdsForTransactionId(updatedEntry.transactionId)))
      SbxLog.info("Box", s"Finished sending ${updatedEntry.totalImageCount} images to box ${box.name}")
      markOutgoingEntryAsFinished(updatedEntry)
      removeTransactionTagValuesForTransactionId(updatedEntry.transactionId)
    }

    context.unbecome
    self ! PollOutgoing
  }

  def handleFileSendFailedForOutgoingEntry(entryImage: OutgoingEntryImage, statusCode: Int, exception: Exception) = {
    log.debug(s"Failed to send file to box ${box.name}: ${exception.getMessage}")
    statusCode match {
      case code if code >= 500 =>
      // server-side error, remote box is most likely down
      case _ =>
        markOutgoingEntryAsFailed(entryImage.entry, s"Cannot send file to box ${box.name}: ${exception.getMessage}")
    }
    context.unbecome
  }

  def updateOutgoingEntryAfterSendingFile(entry: OutgoingEntry) =
    db.withSession { implicit session =>
      val updatedEntry = entry.incrementSent.updateTimestamp
      boxDao.updateOutgoingEntry(updatedEntry)
      updatedEntry
    }

  def markOutgoingEntryAsFinished(entry: OutgoingEntry) =
    db.withSession { implicit session =>
      boxDao.setOutgoingEntryStatus(entry.id, TransactionStatus.FINISHED)
    }

  def markOutgoingEntryAsFailed(entry: OutgoingEntry, logMessage: String) = {
    db.withSession { implicit session =>
      boxDao.setOutgoingEntryStatus(entry.id, TransactionStatus.FAILED)
    }
    SbxLog.error("Box", logMessage)
  }

  def markOutgoingImageAsSent(image: OutgoingImage) =
    db.withSession { implicit session =>
      boxDao.updateOutgoingImage(image.copy(sent = true))
    }

  def removeTransactionTagValuesForTransactionId(transactionId: Long) =
    db.withSession { implicit session =>
      boxDao.removeTransactionTagValuesByTransactionId(transactionId)
    }

  def outgoingImageIdsForTransactionId(transactionId: Long): Seq[Long] =
    db.withSession { implicit session =>
      boxDao.outgoingImagesByTransactionId(transactionId).map(_.imageId)
    }

}

object BoxPushActor {

  def props(box: Box,
            dbProps: DbProps,
            timeout: Timeout): Props =
    Props(new BoxPushActor(box, dbProps, timeout))

}
