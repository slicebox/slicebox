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
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.JsonFormats
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, MetaDataAdded}
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.util.CompressionUtil._
import spray.client.pipelining._
import spray.http.HttpResponse
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.marshalling.marshal
import spray.httpx.unmarshalling.FromResponseUnmarshaller

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

class BoxPollActor(box: Box,
                   implicit val timeout: Timeout,
                   pollInterval: FiniteDuration = 5.seconds,
                   boxServicePath: String = "../../BoxService",
                   metaDataServicePath: String = "../../MetaDataService",
                   storageServicePath: String = "../../StorageService",
                   anonymizationServicePath: String = "../../AnonymizationService") extends Actor with JsonFormats {

  val log = Logging(context.system, this)

  val metaDataService = context.actorSelection(metaDataServicePath)
  val storageService = context.actorSelection(storageServicePath)
  val anonymizationService = context.actorSelection(anonymizationServicePath)
  val boxService = context.actorSelection(boxServicePath)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  def convertOption[T](implicit unmarshaller: FromResponseUnmarshaller[T]): Future[HttpResponse] => Future[Option[T]] =
    (futureResponse: Future[HttpResponse]) => futureResponse.map { response =>
      if (response.status == NotFound) None
      else Some(unmarshal[T](unmarshaller)(response))
    }

  def sendRequestToRemoteBoxPipeline = sendReceive

  val poller = system.scheduler.schedule(pollInterval, pollInterval) {
    self ! PollIncoming
  }

  override def postStop() =
    poller.cancel()

  object EmptyTransactionException extends RuntimeException()
  object RemoteBoxUnavailableException extends RuntimeException()

  def receive = LoggingReceive {
    case PollIncoming =>
      context.become(inTransferState)
      processNextIncomingTransaction().onComplete {
        case Success(_) =>
          self ! TransferFinished
          self ! PollIncoming
        case Failure(e) => e match {
          case e@(EmptyTransactionException | RemoteBoxUnavailableException) =>
            self ! TransferFinished
          case e: Exception =>
            SbxLog.error("Box", s"Failed to receive file from box ${box.name}: ${e.getMessage}")
            self ! TransferFinished
        }
      }
  }

  def inTransferState: Receive = {
    case TransferFinished => context.unbecome()
  }

  def processNextIncomingTransaction(): Future[Unit] = {
    sendPollRequestToRemoteBox.flatMap { transactionImageMaybe =>
      log.debug(s"Remote box answered poll request with outgoing transaction $transactionImageMaybe")

      boxService ! UpdateBoxOnlineStatus(box.id, online = true)

      transactionImageMaybe match {
        case Some(transactionImage) =>
          log.debug(s"Received outgoing transaction $transactionImage")
          fetchFileForRemoteOutgoingTransaction(transactionImage)
        case None =>
          log.debug("Remote outgoing is empty")
          Future.failed(EmptyTransactionException)
      }
    }.recover {
      case exception: Exception =>
        boxService ! UpdateBoxOnlineStatus(box.id, online = false)
        log.debug("Failed to poll remote box: " + exception.getMessage)
        throw RemoteBoxUnavailableException
    }
  }

  def pollRemoteBoxOutgoingPipeline = sendRequestToRemoteBoxPipeline ~> convertOption[OutgoingTransactionImage]

  def sendPollRequestToRemoteBox: Future[Option[OutgoingTransactionImage]] = {
    log.debug(s"Polling remote box ${box.name}")
    pollRemoteBoxOutgoingPipeline(Get(s"${box.baseUrl}/outgoing/poll"))
  }

  def fetchFileForRemoteOutgoingTransaction(transactionImage: OutgoingTransactionImage): Future[Unit] =
    getRemoteOutgoingFile(transactionImage).flatMap { response =>
      val statusCode = response.status.intValue
      if (statusCode >= 200 && statusCode < 300) {
        val bytes = decompress(response.entity.data.toByteArray)
        val dataset = loadDataset(bytes, withPixelData = true, useBulkDataURI = false)

        if (dataset == null)
          signalFetchFileFailedPermanently(transactionImage, new IllegalArgumentException("Dataset could not be read"))

        else
          anonymizationService.ask(ReverseAnonymization(dataset)).mapTo[Attributes].flatMap { reversedDataset =>
            val source = Source(SourceType.BOX, box.name, box.id)
            storageService.ask(CheckDataset(dataset, restrictSopClass = false)).mapTo[Boolean].flatMap { status =>
              metaDataService.ask(AddMetaData(dataset, source)).mapTo[MetaDataAdded].flatMap { metaData =>
                storageService.ask(AddDataset(reversedDataset, source, metaData.image)).mapTo[DatasetAdded].flatMap { datasetAdded =>
                  boxService.ask(UpdateIncoming(box, transactionImage.transaction.id, transactionImage.image.sequenceNumber, transactionImage.transaction.totalImageCount, datasetAdded.image.id, datasetAdded.overwrite)).flatMap { _ =>
                    sendRemoteOutgoingFileCompleted(transactionImage).map { response =>
                    }
                  }
                }
              }
            }
          }.recoverWith {
            case e: Exception =>
              signalFetchFileFailedPermanently(transactionImage, e)
          }

      } else
        statusCode match {
          case s if s < 500 =>
            signalFetchFileFailedPermanently(transactionImage, new RuntimeException(s"Server responded with status code $statusCode and message ${response.message.entity.asString}"))
          case _ =>
            signalFetchFileFailedTemporarily(transactionImage, new RuntimeException(s"Server responded with status code $statusCode and message ${response.message.entity.asString}"))
        }
    }.recoverWith {
      case e: Exception =>
        signalFetchFileFailedTemporarily(transactionImage, e)
    }

  def getRemoteOutgoingFile(transactionImage: OutgoingTransactionImage): Future[HttpResponse] = {
    log.debug(s"Fetching remote outgoing image $transactionImage")
    sendRequestToRemoteBoxPipeline(Get(s"${box.baseUrl}/outgoing?transactionid=${transactionImage.transaction.id}&imageid=${transactionImage.image.id}"))
  }

  // We don't need to wait for done message to be sent since it is not critical that it is received by the remote box
  def sendRemoteOutgoingFileCompleted(transactionImage: OutgoingTransactionImage): Future[HttpResponse] =
    marshal(transactionImage) match {
      case Right(entity) =>
        log.debug(s"Sending done for remote outgoing image $transactionImage")
        sendRequestToRemoteBoxPipeline(Post(s"${box.baseUrl}/outgoing/done", entity))
      case Left(e) =>
        SbxLog.error("Box", s"Failed to send done message to box ${box.name}, data=$transactionImage")
        Future.failed(e)
    }

  def signalFetchFileFailedTemporarily(transactionImage: OutgoingTransactionImage, exception: Exception): Future[Unit] =
    boxService.ask(SetIncomingTransactionStatus(box.id, transactionImage.transaction.id, TransactionStatus.WAITING)).map { _ =>
      log.info("Box", s"Failed to fetch file from box ${box.name}: " + exception.getMessage + ", trying again later.")
      throw RemoteBoxUnavailableException
    }

  def signalFetchFileFailedPermanently(transactionImage: OutgoingTransactionImage, exception: Exception): Future[Unit] =
    sendRemoteOutgoingFileFailed(FailedOutgoingTransactionImage(transactionImage, exception.getMessage)).flatMap { _ =>
      boxService.ask(SetIncomingTransactionStatus(box.id, transactionImage.transaction.id, TransactionStatus.FAILED)).map { _ =>
        throw exception;
      }
    }

  def sendRemoteOutgoingFileFailed(failedTransactionImage: FailedOutgoingTransactionImage): Future[HttpResponse] =
    marshal(failedTransactionImage) match {
      case Right(entity) =>
        sendRequestToRemoteBoxPipeline(Post(s"${box.baseUrl}/outgoing/failed", entity))
      case Left(e) =>
        SbxLog.error("Box", s"Failed to send failed message to box ${box.name}, data=$failedTransactionImage")
        Future.failed(e)
    }

}

object BoxPollActor {
  def props(box: Box, timeout: Timeout): Props = Props(new BoxPollActor(box, timeout))

}
