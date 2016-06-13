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

package se.nimsa.sbx.box

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.ask
import akka.util.Timeout
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.anonymization.AnonymizationProtocol.Anonymize
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomData
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil.toByteArray
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.GetImage
import se.nimsa.sbx.storage.StorageProtocol.GetDataset
import se.nimsa.sbx.util.CompressionUtil.compress
import spray.client.pipelining.{Get, Post, Put, sendReceive}
import spray.http.StatusCodes.NoContent
import spray.http.{HttpData, HttpEntity, HttpResponse}

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

class BoxPushActor(box: Box,
                   implicit val timeout: Timeout,
                   pollInterval: FiniteDuration = 5.seconds,
                   boxServicePath: String = "../../BoxService",
                   metaDataServicePath: String = "../../MetaDataService",
                   storageServicePath: String = "../../StorageService",
                   anonymizationServicePath: String = "../../AnonymizationService") extends Actor {

  val log = Logging(context.system, this)

  val metaDataService = context.actorSelection(metaDataServicePath)
  val storageService = context.actorSelection(storageServicePath)
  val anonymizationService = context.actorSelection(anonymizationServicePath)
  val boxService = context.actorSelection(boxServicePath)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val poller = system.scheduler.schedule(pollInterval, pollInterval) {
    self ! PollOutgoing
  }

  override def postStop() =
    poller.cancel()

  def pipeline = sendReceive

  def receive = LoggingReceive {
    case PollOutgoing =>
      context.become(inTransferState)
      processNextOutgoingTransaction().onComplete {
        case Success(_) =>
          self ! TransferFinished
          self ! PollOutgoing
        case Failure(e) => e match {
          case e@(EmptyTransactionException | RemoteBoxUnavailableException) =>
            self ! TransferFinished
          case e: Exception =>
            SbxLog.error("Box", s"Failed to send file to box ${box.name}: ${e.getMessage}")
            self ! TransferFinished
        }
      }
  }

  def inTransferState: Receive = {
    case TransferFinished => context.unbecome()
  }

  def processNextOutgoingTransaction(): Future[Unit] = {
    boxService.ask(GetNextOutgoingTransactionImage(box.id)).mapTo[Option[OutgoingTransactionImage]].flatMap {
      case Some(transactionImage) => sendFileForOutgoingTransactionImage(transactionImage)
      case None => Future.failed(EmptyTransactionException)
    }
  }

  def sendFileForOutgoingTransactionImage(transactionImage: OutgoingTransactionImage): Future[Unit] =
    boxService.ask(GetOutgoingTagValues(transactionImage)).mapTo[Seq[OutgoingTagValue]]
      .flatMap(transactionTagValues =>
        sendFile(transactionImage, transactionTagValues))

  def sendFile(transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Future[Unit] = {
    log.debug(s"Pushing transaction image $transactionImage with tag values $tagValues")

    pushImagePipeline(transactionImage, tagValues).flatMap(response => {
      val statusCode = response.status.intValue
      if (statusCode >= 200 && statusCode < 300)
        handleFileSentForOutgoingTransaction(transactionImage)
      else {
        val errorMessage = response.entity.asString
        handleFileSendFailedForOutgoingTransaction(transactionImage, statusCode, new Exception(s"File send failed with status code $statusCode: $errorMessage"))
      }
    }).recoverWith {
      case exception: RemoteTransactionFailedException =>
        handleFileSendFailedForOutgoingTransaction(transactionImage, 502, exception)
      case exception: IllegalArgumentException =>
        handleFileSendFailedForOutgoingTransaction(transactionImage, 400, exception)
      case exception: Exception =>
        handleFileSendFailedForOutgoingTransaction(transactionImage, 503, exception)
    }
  }

  def pushImagePipeline(transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Future[HttpResponse] =
    metaDataService.ask(GetImage(transactionImage.image.imageId)).mapTo[Option[Image]].flatMap {
      case Some(image) =>
        storageService.ask(GetDataset(image, withPixelData = true)).mapTo[Option[DicomData]].flatMap {
          case Some(dicomData) =>
            anonymizationService.ask(Anonymize(transactionImage.image.imageId, dicomData.attributes, tagValues.map(_.tagValue))).mapTo[Attributes].flatMap { anonymizedAttributes =>
              val compressedBytes = compress(toByteArray(dicomData.copy(attributes = anonymizedAttributes)))
              pipeline(Post(s"${box.baseUrl}/image?transactionid=${transactionImage.transaction.id}&sequencenumber=${transactionImage.image.sequenceNumber}&totalimagecount=${transactionImage.transaction.totalImageCount}", HttpData(compressedBytes)))
            }
          case None =>
            Future.failed(new IllegalArgumentException("No dataset found for image id " + image.id))
        }
      case None =>
        Future.failed(new IllegalArgumentException("No image found for image id " + transactionImage.image.imageId))
    }

  def handleFileSentForOutgoingTransaction(transactionImage: OutgoingTransactionImage): Future[Unit] = {
    log.debug(s"File sent for outgoing transaction $transactionImage")

    boxService.ask(UpdateOutgoingTransaction(transactionImage)).mapTo[OutgoingTransaction].flatMap { updatedTransaction =>
      if (updatedTransaction.sentImageCount == updatedTransaction.totalImageCount)
        handleTransactionFinished(updatedTransaction)
      else
        Future.successful({})
    }

  }

  def handleFileSendFailedForOutgoingTransaction(transactionImage: OutgoingTransactionImage, statusCode: Int, exception: Exception): Future[Unit] = {
    log.debug(s"Failed to send file to box ${box.name}: ${exception.getMessage}")
    statusCode match {
      case 503 =>
        // service unavailable, remote box is most likely down
        boxService.ask(SetOutgoingTransactionStatus(transactionImage.transaction, TransactionStatus.WAITING)).map {
          throw RemoteBoxUnavailableException
        }
      case 502 =>
        // remote box reported transaction failed. Report it here also
        boxService.ask(SetOutgoingTransactionStatus(transactionImage.transaction, TransactionStatus.FAILED)).map { _ =>
          throw exception
        }
      case _ =>
        // other error in communication. Report the transaction as failed here and on remote
        boxService.ask(SetOutgoingTransactionStatus(transactionImage.transaction, TransactionStatus.FAILED)).flatMap { _ =>
          pipeline(Put(s"${box.baseUrl}/status?transactionid=${transactionImage.transaction.id}", HttpData(TransactionStatus.FAILED.toString)))
            .recover {
              case e: Exception =>
                SbxLog.warn("Box", "Unable to set remote transaction status.")
                HttpResponse(status = NoContent)
            }
            .map { response =>
              throw exception
            }
        }
    }
  }

  def handleTransactionFinished(outgoingTransaction: OutgoingTransaction): Future[Unit] =
    boxService.ask(GetOutgoingImageIdsForTransaction(outgoingTransaction)).mapTo[Seq[Long]].flatMap { imageIds =>
      pipeline(Get(s"${box.baseUrl}/status?transactionid=${outgoingTransaction.id}")).recover {
        case _ =>
          SbxLog.warn("Box", "Unable to get remote status of finished transaction, assuming all is well.")
          HttpResponse(entity = HttpEntity(TransactionStatus.FINISHED.toString))
      }.map { response =>
        val status = TransactionStatus.withName(response.entity.asString)
        status match {
          case TransactionStatus.FINISHED =>
            SbxLog.info("Box", s"Finished sending ${outgoingTransaction.totalImageCount} images to box ${box.name}")
            context.system.eventStream.publish(ImagesSent(Destination(DestinationType.BOX, box.name, box.id), imageIds))
          case _ =>
            throw new RemoteTransactionFailedException()
        }
      }
    }

}

object BoxPushActor {
  def props(box: Box, timeout: Timeout): Props = Props(new BoxPushActor(box, timeout))

}
