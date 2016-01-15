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
      processNextOutgoingTransaction
  }

  def processNextOutgoingTransaction(): Unit =
    nextOutgoingTransaction match {
      case Some(transaction) =>
        context.become(waitForFileSentState)
        sendFileForOutgoingTransaction(transaction)

      case None =>
        context.unbecome
    }

  def waitForFileSentState: Receive = LoggingReceive {

    case FileSent(transactionImage) =>
      handleFileSentForOutgoingTransaction(transactionImage)

    case FileSendFailed(transactionImage, statusCode, exception) =>
      handleFileSendFailedForOutgoingTransaction(transactionImage, statusCode, exception)

    case ReceiveTimeout =>
      log.error("Processing next outgoing transaction timed out")
      context.unbecome
  }

  def nextOutgoingTransaction: Option[OutgoingTransactionImage] =
    db.withSession { implicit session =>
      boxDao.nextOutgoingTransactionImageForBoxId(box.id)
    }

  def sendFileForOutgoingTransaction(transactionImage: OutgoingTransactionImage) = {
    val transactionTagValues = tagValuesForImageIdAndTransactionId(transactionImage)
    sendFile(transactionImage, transactionTagValues)
  }

  def sendFilePipeline = sendReceive

  def pushImagePipeline(transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Future[HttpResponse] = {
    val futureDatasetMaybe = storageService.ask(GetDataset(transactionImage.image.imageId, true)).mapTo[Option[Attributes]]
    futureDatasetMaybe.flatMap(_ match {
      case Some(dataset) =>
        val futureAnonymizedDataset = anonymizationService.ask(Anonymize(transactionImage.image.imageId, dataset, tagValues.map(_.tagValue))).mapTo[Attributes]
        futureAnonymizedDataset flatMap { anonymizedDataset =>
          val compressedBytes = compress(toByteArray(anonymizedDataset))
          sendFilePipeline(Post(s"${box.baseUrl}/image?transactionid=${transactionImage.transaction.id}&totalimagecount=${transactionImage.transaction.totalImageCount}", HttpData(compressedBytes)))
        }
      case None =>
        Future.failed(new IllegalArgumentException("No dataset found for image id " + transactionImage.image.imageId))
    })
  }

  def sendFile(transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]) = {
    pushImagePipeline(transactionImage, tagValues)
      .map(response => {
        val statusCode = response.status.intValue
        if (statusCode >= 200 && statusCode < 300)
          self ! FileSent(transactionImage)
        else {
          val errorMessage = response.entity.asString
          self ! FileSendFailed(transactionImage, statusCode, new Exception(s"File send failed with status code $statusCode: $errorMessage"))
        }
      })
      .recover {
        case exception: IllegalArgumentException =>
          self ! FileSendFailed(transactionImage, 400, exception)
        case exception: Exception =>
          self ! FileSendFailed(transactionImage, 500, exception)
      }
  }

  def tagValuesForImageIdAndTransactionId(transactionImage: OutgoingTransactionImage): Seq[OutgoingTagValue] =
    db.withSession { implicit session =>
      boxDao.tagValuesByOutgoingTransactionImage(transactionImage.transaction.id, transactionImage.image.id)
    }

  def handleFileSentForOutgoingTransaction(transactionImage: OutgoingTransactionImage) = {
    log.debug(s"File sent for outgoing transaction ${transactionImage.transaction.id}")

    markOutgoingImageAsSent(transactionImage.image)
    val updatedTransaction = updateOutgoingTransactionAfterSendingFile(transactionImage.transaction)

    if (updatedTransaction.sentImageCount == updatedTransaction.totalImageCount) {
      context.system.eventStream.publish(ImagesSent(Destination(DestinationType.BOX, box.name, box.id), outgoingImageIdsForTransactionId(updatedTransaction.id)))
      SbxLog.info("Box", s"Finished sending ${updatedTransaction.totalImageCount} images to box ${box.name}")
      markOutgoingTransactionAsFinished(updatedTransaction)
    }

    context.unbecome
    self ! PollOutgoing
  }

  def handleFileSendFailedForOutgoingTransaction(transactionImage: OutgoingTransactionImage, statusCode: Int, exception: Exception) = {
    log.debug(s"Failed to send file to box ${box.name}: ${exception.getMessage}")
    statusCode match {
      case code if code >= 500 =>
      // server-side error, remote box is most likely down
      case _ =>
        markOutgoingTransactionAsFailed(transactionImage.transaction, s"Cannot send file to box ${box.name}: ${exception.getMessage}")
    }
    context.unbecome
  }

  def updateOutgoingTransactionAfterSendingFile(transaction: OutgoingTransaction) =
    db.withSession { implicit session =>
      val updatedTransaction = transaction.incrementSent.updateTimestamp
      boxDao.updateOutgoingTransaction(updatedTransaction)
      updatedTransaction
    }

  def markOutgoingTransactionAsFinished(transaction: OutgoingTransaction) =
    db.withSession { implicit session =>
      boxDao.setOutgoingTransactionStatus(transaction.id, TransactionStatus.FINISHED)
    }

  def markOutgoingTransactionAsFailed(transaction: OutgoingTransaction, logMessage: String) = {
    db.withSession { implicit session =>
      boxDao.setOutgoingTransactionStatus(transaction.id, TransactionStatus.FAILED)
    }
    SbxLog.error("Box", logMessage)
  }

  def markOutgoingImageAsSent(image: OutgoingImage) =
    db.withSession { implicit session =>
      boxDao.updateOutgoingImage(image.copy(sent = true))
    }

  def outgoingImageIdsForTransactionId(transactionId: Long): Seq[Long] =
    db.withSession { implicit session =>
      boxDao.outgoingImagesByOutgoingTransactionId(transactionId).map(_.imageId)
    }

}

object BoxPushActor {

  def props(box: Box,
            dbProps: DbProps,
            timeout: Timeout): Props =
    Props(new BoxPushActor(box, dbProps, timeout))

}
