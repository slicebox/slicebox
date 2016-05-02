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

import akka.actor.{Actor, Props, ReceiveTimeout}
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
                   receiveTimeout: FiniteDuration = 1.minute,
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
  def pollRemoteBoxOutgoingPipeline = sendRequestToRemoteBoxPipeline ~> convertOption[OutgoingTransactionImage]

  def sendPollRequestToRemoteBox: Future[Option[OutgoingTransactionImage]] = {
    log.debug(s"Polling remote box ${box.name}")
    pollRemoteBoxOutgoingPipeline(Get(s"${box.baseUrl}/outgoing/poll"))
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

  def sendRemoteOutgoingFileFailed(failedTransactionImage: FailedOutgoingTransactionImage): Future[HttpResponse] =
    marshal(failedTransactionImage) match {
      case Right(entity) =>
        sendRequestToRemoteBoxPipeline(Post(s"${box.baseUrl}/outgoing/failed", entity))
      case Left(e) =>
        SbxLog.error("Box", s"Failed to send failed message to box ${box.name}, data=$failedTransactionImage")
        Future.failed(e)
    }

  val poller = system.scheduler.schedule(pollInterval, pollInterval) {
    self ! PollRemoteBox
  }

  context.setReceiveTimeout(receiveTimeout)

  override def postStop() =
    poller.cancel()

  def receive = LoggingReceive {
    case PollRemoteBox => pollRemoteBox()
  }

  def waitForPollRemoteOutgoingState: PartialFunction[Any, Unit] = LoggingReceive {
    case RemoteOutgoingEmpty =>
      log.debug("Remote outgoing is empty")
      context.unbecome

    case RemoteOutgoingTransactionImageFound(transactionImage) =>
      log.debug(s"Received outgoing transaction $transactionImage")

      context.become(waitForFileFetchedState)
      fetchFileForRemoteOutgoingTransaction(transactionImage)

    case PollRemoteBoxFailed(exception) =>
      log.debug("Failed to poll remote box: " + exception.getMessage)
      context.unbecome

    case ReceiveTimeout =>
      log.error("Polling sequence timed out while waiting for remote box state")
      context.unbecome
  }

  def waitForFileFetchedState: PartialFunction[Any, Unit] = LoggingReceive {
    case RemoteOutgoingFileFetched(transactionImage, imageId, overwrite) =>
      boxService.ask(UpdateIncoming(box, transactionImage.transaction.id, transactionImage.image.sequenceNumber, transactionImage.transaction.totalImageCount, imageId, overwrite))
        .foreach { _ =>
          sendRemoteOutgoingFileCompleted(transactionImage).foreach { response =>
            pollRemoteBox()
          }
        }

    case FetchFileFailedTemporarily(transactionImage, exception) =>
      log.info("Box", s"Failed to fetch file from box ${box.name}: " + exception.getMessage + ", trying again later.")
      boxService ! SetIncomingTransactionStatus(box.id, transactionImage, TransactionStatus.WAITING)
      context.unbecome

    case FetchFileFailedPermanently(transactionImage, exception) =>
      sendRemoteOutgoingFileFailed(FailedOutgoingTransactionImage(transactionImage, exception.getMessage))
      SbxLog.error("Box", s"Failed to fetch file from box ${box.name}: " + exception.getMessage)
      boxService ! SetIncomingTransactionStatus(box.id, transactionImage, TransactionStatus.FAILED)
      context.unbecome

    case ReceiveTimeout =>
      log.error("Polling sequence timed out while fetching data")
      context.unbecome
  }

  def pollRemoteBox(): Unit = {
    context.become(waitForPollRemoteOutgoingState)

    sendPollRequestToRemoteBox
      .map(transactionImageMaybe => {
        log.debug(s"Remote box answered poll request with outgoing transaction $transactionImageMaybe")

        boxService ! UpdateBoxOnlineStatus(box.id, online = true)

        transactionImageMaybe match {
          case Some(transactionImage) =>
            self ! RemoteOutgoingTransactionImageFound(transactionImage)
          case None =>
            self ! RemoteOutgoingEmpty
        }
      })
      .recover {
        case exception: Exception =>
          boxService ! UpdateBoxOnlineStatus(box.id, online = false)
          self ! PollRemoteBoxFailed(exception)
      }
  }

  def fetchFileForRemoteOutgoingTransaction(transactionImage: OutgoingTransactionImage): Unit =
    getRemoteOutgoingFile(transactionImage)
      .onComplete {

        case Success(response) =>
          if (response.status.intValue < 300) {
            val bytes = decompress(response.entity.data.toByteArray)
            val dataset = loadDataset(bytes, withPixelData = true, useBulkDataURI = false)

            if (dataset == null)
              self ! FetchFileFailedPermanently(transactionImage, new IllegalArgumentException("Dataset could not be read"))

            else
              anonymizationService.ask(ReverseAnonymization(dataset)).mapTo[Attributes]
                .onComplete {

                  case Success(reversedDataset) =>
                    val source = Source(SourceType.BOX, box.name, box.id)
                    storageService.ask(CheckDataset(dataset)).mapTo[Boolean].flatMap { status =>
                      metaDataService.ask(AddMetaData(dataset, source)).mapTo[MetaDataAdded].flatMap { metaData =>
                        storageService.ask(AddDataset(reversedDataset, source, metaData.image)).mapTo[DatasetAdded]
                      }
                    }.onComplete {
                        case Success(DatasetAdded(image, overwrite)) =>
                          self ! RemoteOutgoingFileFetched(transactionImage, image.id, overwrite)
                        case Failure(exception) =>
                          self ! FetchFileFailedPermanently(transactionImage, exception)
                      }

                  case Failure(exception) =>
                    self ! FetchFileFailedPermanently(transactionImage, exception)
                }
          } else
            response.status.intValue match {
              case s if s < 500 =>
                self ! FetchFileFailedPermanently(transactionImage, new RuntimeException(s"Server responded with status code ${response.status.intValue} and message ${response.message.entity.asString}"))
              case _ =>
                self ! FetchFileFailedTemporarily(transactionImage, new RuntimeException(s"Server responded with status code ${response.status.intValue} and message ${response.message.entity.asString}"))
            }

        case Failure(exception) =>
          self ! FetchFileFailedTemporarily(transactionImage, exception)
      }

}

object BoxPollActor {
  def props(box: Box, timeout: Timeout): Props = Props(new BoxPollActor(box, timeout))

}
