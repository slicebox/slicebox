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

package se.nimsa.sbx.box

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.NoContent
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source => StreamSource}
import akka.util.{ByteString, Timeout}
import se.nimsa.dcm4che.streams.DicomFlows.TagModification
import se.nimsa.sbx.anonymization.AnonymizationServiceCalls
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.streams.DicomStreams
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.GetImage
import se.nimsa.sbx.storage.StorageService

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

class BoxPushActor(box: Box,
                   storage: StorageService,
                   pollInterval: FiniteDuration = 5.seconds,
                   boxServicePath: String = "../../BoxService",
                   metaDataServicePath: String = "../../MetaDataService",
                   anonymizationServicePath: String = "../../AnonymizationService")
                  (implicit val timeout: Timeout) extends Actor with AnonymizationServiceCalls {

  val log = Logging(context.system, this)

  val metaDataService = context.actorSelection(metaDataServicePath)
  val anonymizationService = context.actorSelection(anonymizationServicePath)
  val boxService = context.actorSelection(boxServicePath)

  implicit val system = context.system
  implicit val executor = context.dispatcher
  implicit val materializer = ActorMaterializer()

  val poller = system.scheduler.schedule(pollInterval, pollInterval) {
    self ! PollOutgoing
  }

  override def postStop() =
    poller.cancel()

  val pool = Http().superPool[String]()

  override def callAnonymizationService[R: ClassTag](message: Any) = anonymizationService.ask(message).mapTo[R]

  def receive = LoggingReceive {
    case PollOutgoing =>
      context.become(inTransferState)
      processNextOutgoingTransaction().onComplete {
        case Success(_) =>
          self ! TransferFinished
          self ! PollOutgoing
        case Failure(e) => e match {
          case _@(EmptyTransactionException | RemoteBoxUnavailableException) =>
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
        Unmarshal(response).to[String].flatMap { errorMessage =>
          handleFileSendFailedForOutgoingTransaction(transactionImage, statusCode, new Exception(s"File send failed with status code $statusCode: $errorMessage"))
        }
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
        val tagMods = tagValues.map(ttv =>
          TagModification(ttv.tagValue.tag, _ => ByteString(ttv.tagValue.value.getBytes("US-ASCII")), insert = true))
        val source = DicomStreams.anonymizedDicomDataSource(storage.fileSource(image), anonymizationQuery, anonymizationInsert, tagMods)
        val uri = s"${box.baseUrl}/image?transactionid=${transactionImage.transaction.id}&sequencenumber=${transactionImage.image.sequenceNumber}&totalimagecount=${transactionImage.transaction.totalImageCount}"
        val connectionId = s"${transactionImage.transaction.id},${transactionImage.image.sequenceNumber}"
        sliceboxRequest(HttpMethods.POST, uri, HttpEntity(ContentTypes.`application/octet-stream`, source), connectionId)
      case None =>
        Future.failed(new IllegalArgumentException("Image not found for image id " + transactionImage.image.imageId))
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
          val uri = s"${box.baseUrl}/status?transactionid=${transactionImage.transaction.id}"
          val connectionId = transactionImage.transaction.id.toString
          sliceboxRequest(HttpMethods.PUT, uri, HttpEntity(TransactionStatus.FAILED.toString), connectionId)
            .recover {
              case _: Exception =>
                SbxLog.warn("Box", "Unable to set remote transaction status.")
                HttpResponse(status = NoContent)
            }
            .map { _ =>
              throw exception
            }
        }
    }
  }

  def handleTransactionFinished(outgoingTransaction: OutgoingTransaction): Future[Unit] =
    boxService.ask(GetOutgoingImageIdsForTransaction(outgoingTransaction)).mapTo[Seq[Long]].flatMap { imageIds =>
      val uri = s"${box.baseUrl}/status?transactionid=${outgoingTransaction.id}"
      val connectionId = outgoingTransaction.id.toString
      sliceboxRequest(HttpMethods.GET, uri, HttpEntity.Empty, connectionId)
        .recover {
          case _ =>
            SbxLog.warn("Box", "Unable to get remote status of finished transaction, assuming all is well.")
            HttpResponse(entity = HttpEntity(TransactionStatus.FINISHED.toString))
        }.flatMap { response =>
        Unmarshal(response).to[String].map { statusString =>
          val status = TransactionStatus.withName(statusString)
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

  private def sliceboxRequest(method: HttpMethod, uri: String, entity: RequestEntity, connectionId: String): Future[HttpResponse] =
    StreamSource
      .single(HttpRequest(method = method, uri = uri, entity = entity) -> connectionId)
      .via(pool)
      .runWith(Sink.head)
      .map {
        case (Success(response), _) => response
        case (Failure(error), _) => throw error
      }
}

object BoxPushActor {
  def props(box: Box, storageService: StorageService, timeout: Timeout): Props = Props(new BoxPushActor(box, storageService)(timeout))

}
