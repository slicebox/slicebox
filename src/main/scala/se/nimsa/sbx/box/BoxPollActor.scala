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
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import akka.pattern.ask
import spray.client.pipelining._
import spray.http.HttpResponse
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.httpx.marshalling.marshal
import spray.httpx.unmarshalling.FromResponseUnmarshaller
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.JsonFormats
import se.nimsa.sbx.storage.StorageProtocol.DatasetReceived
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import BoxProtocol._
import akka.actor.ReceiveTimeout
import java.util.Date
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.VR
import se.nimsa.sbx.dicom.DicomProperty.PatientName
import org.dcm4che3.data.Tag
import akka.util.Timeout
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.storage.StorageProtocol._
import scala.util.Success
import scala.util.Failure
import se.nimsa.sbx.util.CompressionUtil._

class BoxPollActor(box: Box,
                   dbProps: DbProps,
                   implicit val timeout: Timeout,
                   pollInterval: FiniteDuration = 5.seconds,
                   receiveTimeout: FiniteDuration = 1.minute,
                   storageServicePath: String = "../../StorageService",
                   anonymizationServicePath: String = "../../AnonymizationService") extends Actor with JsonFormats {

  val log = Logging(context.system, this)

  val db = dbProps.db
  val boxDao = new BoxDAO(dbProps.driver)

  val storageService = context.actorSelection(storageServicePath)
  val anonymizationService = context.actorSelection(anonymizationServicePath)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  def convertOption[T](implicit unmarshaller: FromResponseUnmarshaller[T]): Future[HttpResponse] => Future[Option[T]] =
    (futureResponse: Future[HttpResponse]) => futureResponse.map { response =>
      if (response.status == StatusCodes.NotFound) None
      else Some(unmarshal[T](unmarshaller)(response))
    }

  def sendRequestToRemoteBoxPipeline = sendReceive
  def pollRemoteBoxOutgoingPipeline = sendRequestToRemoteBoxPipeline ~> convertOption[OutgoingTransactionImage]

  def sendPollRequestToRemoteBox: Future[Option[OutgoingTransactionImage]] =
    pollRemoteBoxOutgoingPipeline(Get(s"${box.baseUrl}/outgoing/poll"))

  def getRemoteOutgoingFile(transactionImage: OutgoingTransactionImage): Future[HttpResponse] =
    sendRequestToRemoteBoxPipeline(Get(s"${box.baseUrl}/outgoing?transactionid=${transactionImage.transaction.id}&imageid=${transactionImage.image.imageId}"))

  // We don't need to wait for done message to be sent since it is not criticalf that it is received by the remote box
  def sendRemoteOutgoingFileCompleted(transactionImage: OutgoingTransactionImage): Future[HttpResponse] =
    marshal(transactionImage) match {
      case Right(entity) =>
        sendRequestToRemoteBoxPipeline(Post(s"${box.baseUrl}/outgoing/done", entity))
      case Left(e) =>
        SbxLog.error("Box", s"Failed to send done message to remote box (${box.name},${transactionImage.transaction.id},${transactionImage.image.imageId})")
        Future.failed(e)
    }

  def sendRemoteOutgoingFileFailed(failedTransactionImage: FailedOutgoingTransactionImage): Future[HttpResponse] =
    marshal(failedTransactionImage) match {
      case Right(entity) =>
        sendRequestToRemoteBoxPipeline(Post(s"${box.baseUrl}/outgoing/failed", entity))
      case Left(e) =>
        SbxLog.error("Box", s"Failed to send failed message to remote box (${box.name},${failedTransactionImage.transactionImage.transaction.id},${failedTransactionImage.transactionImage.image.imageId})")
        Future.failed(e)
    }

  val poller = system.scheduler.schedule(pollInterval, pollInterval) {
    self ! PollRemoteBox
  }

  context.setReceiveTimeout(receiveTimeout)

  override def postStop() =
    poller.cancel()

  def receive = LoggingReceive {
    case PollRemoteBox => pollRemoteBox
  }

  def waitForPollRemoteOutgoingState: PartialFunction[Any, Unit] = LoggingReceive {
    case RemoteOutgoingEmpty =>
      log.debug("Remote outgoing is empty")
      context.unbecome

    case RemoteOutgoingTransactionImageFound(transactionImage) =>
      log.debug(s"Received outgoing transaction ${transactionImage}")

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
    case RemoteOutgoingFileFetched(transactionImage, imageId) =>
      updateIncoming(box.id, box.name, transactionImage.transaction.id, transactionImage.transaction.totalImageCount, imageId)
      sendRemoteOutgoingFileCompleted(transactionImage).foreach { response =>
        pollRemoteBox()
      }

    case FetchFileFailed(transactionImage, exception) =>
      SbxLog.info("Box", s"Failed to fetch file from box ${box.name}: " + exception.getMessage)
      context.unbecome

    case HandlingFetchedFileFailed(transactionImage, exception) =>
      sendRemoteOutgoingFileFailed(FailedOutgoingTransactionImage(transactionImage, exception.getMessage))
      SbxLog.error("Box", s"Failed to handle fetched file from box ${box.name}: " + exception.getMessage)
      context.unbecome

    case ReceiveTimeout =>
      log.error("Polling sequence timed out while fetching data")
      context.unbecome
  }

  def pollRemoteBox(): Unit = {
    context.become(waitForPollRemoteOutgoingState)

    sendPollRequestToRemoteBox
      .map(transactionImageMaybe => {
        updateBoxOnlineStatus(true)

        transactionImageMaybe match {
          case Some(transactionImage) =>
            self ! RemoteOutgoingTransactionImageFound(transactionImage)
          case None =>
            self ! RemoteOutgoingEmpty
        }
      })
      .recover {
        case exception: Exception =>
          updateBoxOnlineStatus(false)
          self ! PollRemoteBoxFailed(exception)
      }
  }

  def fetchFileForRemoteOutgoingTransaction(transactionImage: OutgoingTransactionImage): Unit =
    getRemoteOutgoingFile(transactionImage)
      .onComplete {

        case Success(response) =>
          if (response.status.intValue < 300) {
            val bytes = decompress(response.entity.data.toByteArray)
            val dataset = loadDataset(bytes, true)

            if (dataset == null)
              self ! HandlingFetchedFileFailed(transactionImage, new IllegalArgumentException("Dataset could not be read"))

            else
              anonymizationService.ask(ReverseAnonymization(dataset)).mapTo[Attributes]
                .onComplete {

                  case Success(reversedDataset) =>
                    val source = Source(SourceType.BOX, box.name, box.id)
                    storageService.ask(AddDataset(reversedDataset, source))
                      .onComplete {

                        case Success(DatasetAdded(image, source)) =>
                          self ! RemoteOutgoingFileFetched(transactionImage, image.id)
                        case Success(_) =>
                          self ! HandlingFetchedFileFailed(transactionImage, new Exception("Unexpected response when adding dataset"))
                        case Failure(exception) =>
                          self ! HandlingFetchedFileFailed(transactionImage, exception)
                      }

                  case Failure(exception) =>
                    self ! HandlingFetchedFileFailed(transactionImage, exception)
                }
          } else
            self ! FetchFileFailed(transactionImage, new RuntimeException("Server responded with status code " + response.status.intValue))

        case Failure(exception) =>
          self ! FetchFileFailed(transactionImage, exception)
      }

  def updateIncoming(boxId: Long, boxName: String, outgoingTransactionId: Long, totalImageCount: Long, imageId: Long): Unit = {
    db.withSession { implicit session =>

      val existingTransaction = boxDao.incomingTransactionByOutgoingTransactionId(boxId, outgoingTransactionId)
        .getOrElse(boxDao.insertIncomingTransaction(IncomingTransaction(-1, boxId, boxName, outgoingTransactionId, 0, totalImageCount, System.currentTimeMillis, TransactionStatus.WAITING)))
      val incomingTransaction = existingTransaction.incrementReceived.updateTimestamp.copy(totalImageCount = totalImageCount)
      boxDao.updateIncomingTransaction(incomingTransaction)
      boxDao.insertIncomingImage(IncomingImage(-1, incomingTransaction.id, imageId))

      if (incomingTransaction.receivedImageCount == incomingTransaction.totalImageCount)
        db.withSession { implicit session =>
          boxDao.setIncomingTransactionStatus(incomingTransaction.id, TransactionStatus.FINISHED)
          SbxLog.info("Box", s"Received ${totalImageCount} files from box boxName")
        }
    }
  }

  def updateBoxOnlineStatus(online: Boolean): Unit =
    db.withSession { implicit session =>
      boxDao.updateBoxOnlineStatus(box.id, online)
    }
}

object BoxPollActor {
  def props(box: Box,
            dbProps: DbProps,
            timeout: Timeout): Props = Props(new BoxPollActor(box, dbProps, timeout))

}
