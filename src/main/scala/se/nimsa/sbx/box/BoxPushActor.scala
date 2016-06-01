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

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import org.dcm4che3.data.Attributes
import BoxProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout
import se.nimsa.sbx.anonymization.AnonymizationProtocol.Anonymize
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.DicomUtil.toByteArray
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.GetImage
import se.nimsa.sbx.storage.StorageProtocol.GetDataset
import se.nimsa.sbx.util.CompressionUtil.compress
import spray.client.pipelining.{Get, Post}
import spray.client.pipelining.sendReceive
import spray.http.{HttpData, HttpResponse}

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

  object EmptyTransactionException extends RuntimeException()
  object RemoteBoxUnavailableException extends RuntimeException()

  def sendFilePipeline = sendReceive

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
      case exception: IllegalArgumentException =>
        handleFileSendFailedForOutgoingTransaction(transactionImage, 400, exception)
      case exception: Exception =>
        handleFileSendFailedForOutgoingTransaction(transactionImage, 500, exception)
    }
  }

  def pushImagePipeline(transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Future[HttpResponse] =
    metaDataService.ask(GetImage(transactionImage.image.imageId)).mapTo[Option[Image]].flatMap {
      case Some(image) =>
        storageService.ask(GetDataset(image, withPixelData = true)).mapTo[Option[Attributes]].flatMap {
          case Some(dataset) =>
            anonymizationService.ask(Anonymize(transactionImage.image.imageId, dataset, tagValues.map(_.tagValue))).mapTo[Attributes].flatMap { anonymizedDataset =>
              val compressedBytes = compress(toByteArray(anonymizedDataset))
              sendFilePipeline(Post(s"${box.baseUrl}/image?transactionid=${transactionImage.transaction.id}&sequencenumber=${transactionImage.image.sequenceNumber}&totalimagecount=${transactionImage.transaction.totalImageCount}", HttpData(compressedBytes)))
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
      case code if code >= 500 =>
        // server-side error, remote box is most likely down
        boxService.ask(SetOutgoingTransactionStatus(transactionImage.transaction, TransactionStatus.WAITING)).map {
          throw RemoteBoxUnavailableException
        }

      case _ =>
        boxService.ask(SetOutgoingTransactionStatus(transactionImage.transaction, TransactionStatus.FAILED)).map {
          throw exception
        }
    }
  }

  def handleTransactionFinished(outgoingTransaction: OutgoingTransaction): Future[Unit] =
    boxService.ask(GetOutgoingImageIdsForTransaction(outgoingTransaction)).mapTo[Seq[Long]].flatMap { imageIds =>
      sendFilePipeline(Get(s"${box.baseUrl}/status?transactionid=${outgoingTransaction.id}")).flatMap { response =>
        val status = TransactionStatus.withName(response.entity.asString)
        status match {
          case TransactionStatus.FINISHED =>
            SbxLog.info("Box", s"Finished sending ${outgoingTransaction.totalImageCount} images to box ${box.name}")
            context.system.eventStream.publish(ImagesSent(Destination(DestinationType.BOX, box.name, box.id), imageIds))
            Future.successful({})
          case _ =>
            boxService.ask(SetOutgoingTransactionStatus(outgoingTransaction, TransactionStatus.FAILED)).map {
              throw new Exception("Remote box reported transaction failed")
            }
        }
      }.recover {
        case _ =>
          SbxLog.warn("Box", "Unable to get remote status of finished transaction, assuming all is well.")
      }
    }

}

object BoxPushActor {
  def props(box: Box, timeout: Timeout): Props = Props(new BoxPushActor(box, timeout))

}
