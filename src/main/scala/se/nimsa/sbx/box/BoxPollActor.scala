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
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Compression
import akka.util.Timeout
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.Contexts
import se.nimsa.sbx.dicom.streams.DicomStreamOps
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.storage.StorageService

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

class BoxPollActor(box: Box,
                   storage: StorageService,
                   pollInterval: FiniteDuration = 5.seconds,
                   boxServicePath: String = "../../BoxService",
                   metaDataServicePath: String = "../../MetaDataService",
                   storageServicePath: String = "../../StorageService",
                   anonymizationServicePath: String = "../../AnonymizationService")
                  (implicit val timeout: Timeout) extends Actor with DicomStreamOps with BoxJsonFormats with PlayJsonSupport {

  val log = Logging(context.system, this)

  val metaDataService = context.actorSelection(metaDataServicePath)
  val storageService = context.actorSelection(storageServicePath)
  val anonymizationService = context.actorSelection(anonymizationServicePath)
  val boxService = context.actorSelection(boxServicePath)

  implicit val system = context.system
  implicit val executor = context.dispatcher
  implicit val materializer = ActorMaterializer()

  val poller = system.scheduler.schedule(pollInterval, pollInterval) {
    self ! PollIncoming
  }

  override def postStop() =
    poller.cancel()

  override def callAnonymizationService[R: ClassTag](message: Any) = anonymizationService.ask(message).mapTo[R]
  override def callStorageService[R: ClassTag](message: Any) = storageService.ask(message).mapTo[R]
  override def callMetaDataService[R: ClassTag](message: Any) = metaDataService.ask(message).mapTo[R]

  def receive = LoggingReceive {
    case PollIncoming =>
      context.become(inTransferState)
      processNextIncomingTransaction().onComplete {
        case Success(_) =>
          self ! TransferFinished
          self ! PollIncoming
        case Failure(e) => e match {
          case _@(EmptyTransactionException | RemoteBoxUnavailableException) =>
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
    val futurePoll = sendPollRequestToRemoteBox.recover {
      case exception: Exception =>
        boxService ! UpdateBoxOnlineStatus(box.id, online = false)
        log.debug("Failed to poll remote box: " + exception.getMessage)
        throw RemoteBoxUnavailableException
    }

    futurePoll.flatMap { transactionImageMaybe =>
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
    }
  }

  def sendPollRequestToRemoteBox: Future[Option[OutgoingTransactionImage]] = {
    log.debug(s"Polling remote box ${box.name}")
    val uri = s"${box.baseUrl}/outgoing/poll"
    sliceboxRequest(HttpMethods.GET, uri, HttpEntity.Empty)
      .flatMap {
        case response if response.status == NotFound =>
          Future.successful(None)
        case response =>
          Unmarshal(response).to[OutgoingTransactionImage].map(Some(_))
      }
  }

  def fetchFileForRemoteOutgoingTransaction(transactionImage: OutgoingTransactionImage): Future[Unit] = {
    val futureResponse = getRemoteOutgoingFile(transactionImage).recoverWith {
      case e: Exception =>
        signalFetchFileFailedTemporarily(transactionImage, e).map { _ =>
          HttpResponse() // just to get type right, future is failed at this point
        }
    }

    futureResponse.flatMap { response =>
      val statusCode = response.status.intValue
      if (statusCode >= 200 && statusCode < 300) {
        val source = Source(SourceType.BOX, box.name, box.id)
        storeData(response.entity.dataBytes.via(Compression.inflate()), source, storage, Contexts.extendedContexts).flatMap { metaData =>
          boxService.ask(UpdateIncoming(box, transactionImage.transaction.id, transactionImage.image.sequenceNumber, transactionImage.transaction.totalImageCount, metaData.image.id, metaData.imageAdded)).flatMap {
            case IncomingUpdated(transaction) =>
              transaction.status match {
                case TransactionStatus.FAILED =>
                  throw new RuntimeException("Invalid transaction")
                case _ =>
                  sendRemoteOutgoingFileCompleted(transactionImage).map { _ =>
                    system.eventStream.publish(ImageAdded(metaData.image, source, !metaData.imageAdded))
                  }
              }
          }
        }.recoverWith {
          case e: Exception =>
            signalFetchFileFailedPermanently(transactionImage, e)
        }
      } else
        Unmarshal(response).to[String].flatMap { message =>
          signalFetchFileFailedPermanently(transactionImage, new RuntimeException(s"Server responded with status code $statusCode and message $message"))
        }
    }
  }

  def getRemoteOutgoingFile(transactionImage: OutgoingTransactionImage): Future[HttpResponse] = {
    log.debug(s"Fetching remote outgoing image $transactionImage")
    val uri = s"${box.baseUrl}/outgoing?transactionid=${transactionImage.transaction.id}&imageid=${transactionImage.image.id}"
    sliceboxRequest(HttpMethods.GET, uri, HttpEntity.Empty)
  }

  def sendRemoteOutgoingFileCompleted(transactionImage: OutgoingTransactionImage): Future[HttpResponse] =
    Marshal(transactionImage).to[MessageEntity].flatMap { entity =>
      log.debug(s"Sending done for remote outgoing image $transactionImage")
      val uri = s"${box.baseUrl}/outgoing/done"
      // We don't need to wait for done message to be sent since it is not critical that it is received by the remote box
      sliceboxRequest(HttpMethods.POST, uri, entity)
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
    Marshal(failedTransactionImage).to[MessageEntity].flatMap { entity =>
      val uri = s"${box.baseUrl}/outgoing/failed"
      sliceboxRequest(HttpMethods.POST, uri, entity)
    }

  protected def sliceboxRequest(method: HttpMethod, uri: String, entity: MessageEntity): Future[HttpResponse] =
    Http().singleRequest(HttpRequest(method = method, uri = uri, entity = entity))

}

object BoxPollActor {
  def props(box: Box, storage: StorageService, timeout: Timeout): Props = Props(new BoxPollActor(box, storage)(timeout))

}
