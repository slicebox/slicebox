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
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source => StreamSource}
import akka.util.{ByteString, Timeout}
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, MetaDataAdded}
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.util.CompressionUtil._

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

class BoxPollActor(box: Box,
                   implicit val timeout: Timeout,
                   pollInterval: FiniteDuration = 5.seconds,
                   boxServicePath: String = "../../BoxService",
                   metaDataServicePath: String = "../../MetaDataService",
                   storageServicePath: String = "../../StorageService",
                   anonymizationServicePath: String = "../../AnonymizationService") extends Actor with BoxJsonFormats with PlayJsonSupport {

  val log = Logging(context.system, this)

  val metaDataService = context.actorSelection(metaDataServicePath)
  val storageService = context.actorSelection(storageServicePath)
  val anonymizationService = context.actorSelection(anonymizationServicePath)
  val boxService = context.actorSelection(boxServicePath)

  implicit val system = context.system
  implicit val ec = context.dispatcher
  implicit val materializer = ActorMaterializer()

  val pool = Http().superPool[String]()

  val poller = system.scheduler.schedule(pollInterval, pollInterval) {
    self ! PollIncoming
  }

  override def postStop() =
    poller.cancel()

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
    val connectionId = s"poll-${box.id}"
    sliceboxRequest(HttpMethods.GET, uri, HttpEntity.Empty, connectionId)
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
        response.entity.dataBytes.fold(ByteString.empty)(_ ++ _).runWith(Sink.head).flatMap { compressedBytes =>
          val bytes = decompress(compressedBytes.toArray)
          val dicomData = loadDicomData(bytes, withPixelData = true)

          if (dicomData == null)
            signalFetchFileFailedPermanently(transactionImage, new IllegalArgumentException("Dicom data could not be read"))

          else
            anonymizationService.ask(ReverseAnonymization(dicomData.attributes)).mapTo[Attributes].flatMap { reversedAttributes =>
              val source = Source(SourceType.BOX, box.name, box.id)
              storageService.ask(CheckDicomData(dicomData, useExtendedContexts = true)).mapTo[Boolean].flatMap { status =>
                metaDataService.ask(AddMetaData(dicomData.attributes, source)).mapTo[MetaDataAdded].flatMap { metaData =>
                  storageService.ask(AddDicomData(dicomData.copy(attributes = reversedAttributes), source, metaData.image)).mapTo[DicomDataAdded].flatMap { dicomDataAdded =>
                    boxService.ask(UpdateIncoming(box, transactionImage.transaction.id, transactionImage.image.sequenceNumber, transactionImage.transaction.totalImageCount, dicomDataAdded.image.id, dicomDataAdded.overwrite)).flatMap {
                      case IncomingUpdated(transaction) =>
                        transaction.status match {
                          case TransactionStatus.FAILED =>
                            throw new RuntimeException("Invalid transaction")
                          case _ =>
                            sendRemoteOutgoingFileCompleted(transactionImage).map(_ => {})
                        }
                    }
                  }
                }
              }
            }.recoverWith {
              case e: Exception =>
                signalFetchFileFailedPermanently(transactionImage, e)
            }
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
    val connectionId = s"${transactionImage.transaction.id},${transactionImage.image.id}"
    sliceboxRequest(HttpMethods.GET, uri, HttpEntity.Empty, connectionId)
  }

  def sendRemoteOutgoingFileCompleted(transactionImage: OutgoingTransactionImage): Future[HttpResponse] =
    Marshal(transactionImage).to[MessageEntity].flatMap { entity =>
      log.debug(s"Sending done for remote outgoing image $transactionImage")
      val uri = s"${box.baseUrl}/outgoing/done"
      val connectionId = s"done-${box.id}"
      // We don't need to wait for done message to be sent since it is not critical that it is received by the remote box
      sliceboxRequest(HttpMethods.POST, uri, entity, connectionId)
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
      val connectionId = s"failed-${box.id}"
      sliceboxRequest(HttpMethods.POST, uri, entity, connectionId)
    }

  private def sliceboxRequest(method: HttpMethod, uri: String, entity: MessageEntity, connectionId: String): Future[HttpResponse] =
    StreamSource
      .single(HttpRequest(method = method, uri = uri, entity = entity) -> connectionId)
      .via(pool)
      .runWith(Sink.head)
      .map {
        case (Success(response), _) => response
        case (Failure(error), _) => throw error
      }

}

object BoxPollActor {
  def props(box: Box, timeout: Timeout): Props = Props(new BoxPollActor(box, timeout))

}
