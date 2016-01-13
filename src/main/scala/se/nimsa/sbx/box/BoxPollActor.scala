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
  def pollRemoteBoxOutgoingPipeline = sendRequestToRemoteBoxPipeline ~> convertOption[OutgoingEntryAndImage]

  def sendPollRequestToRemoteBox: Future[Option[OutgoingEntryAndImage]] =
    pollRemoteBoxOutgoingPipeline(Get(s"${box.baseUrl}/outgoing/poll"))

  def getRemoteOutgoingFile(remoteOutgoingEntryAndImage: OutgoingEntryAndImage): Future[HttpResponse] =
    sendRequestToRemoteBoxPipeline(Get(s"${box.baseUrl}/outgoing?transactionid=${remoteOutgoingEntryAndImage.outgoingEntry.transactionId}&imageid=${remoteOutgoingEntryAndImage.outgoingImage.imageId}"))

  // We don't need to wait for done message to be sent since it is not criticalf that it is received by the remote box
  def sendRemoteOutgoingFileCompleted(remoteOutgoingEntryAndImage: OutgoingEntryAndImage): Future[HttpResponse] =
    marshal(remoteOutgoingEntryAndImage) match {
      case Right(entity) =>
        sendRequestToRemoteBoxPipeline(Post(s"${box.baseUrl}/outgoing/done", entity))
      case Left(e) =>
        SbxLog.error("Box", s"Failed to send done message to remote box (${box.name},${remoteOutgoingEntryAndImage.outgoingEntry.transactionId},${remoteOutgoingEntryAndImage.outgoingImage.imageId})")
        Future.failed(e)
    }

  def sendRemoteOutgoingFileFailed(failedOutgoingEntry: FailedOutgoingEntry): Future[HttpResponse] =
    marshal(failedOutgoingEntry) match {
      case Right(entity) =>
        sendRequestToRemoteBoxPipeline(Post(s"${box.baseUrl}/outgoing/failed", entity))
      case Left(e) =>
        SbxLog.error("Box", s"Failed to send failed message to remote box (${box.name},${failedOutgoingEntry.outgoingEntryAndImage.outgoingEntry.transactionId},${failedOutgoingEntry.outgoingEntryAndImage.outgoingImage.imageId})")
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

    case RemoteOutgoingEntryAndImageFound(remoteOutgoingEntryAndImage) =>
      log.debug(s"Received outgoing entry ${remoteOutgoingEntryAndImage}")

      context.become(waitForFileFetchedState)
      fetchFileForRemoteOutgoingEntry(remoteOutgoingEntryAndImage)

    case PollRemoteBoxFailed(exception) =>
      log.debug("Failed to poll remote box: " + exception.getMessage)
      context.unbecome

    case ReceiveTimeout =>
      log.error("Polling sequence timed out while waiting for remote box state")
      context.unbecome
  }

  def waitForFileFetchedState: PartialFunction[Any, Unit] = LoggingReceive {
    case RemoteOutgoingFileFetched(remoteOutgoingEntryAndImage, imageId) =>
      updateIncoming(box.id, box.name, remoteOutgoingEntryAndImage.outgoingEntry.transactionId, remoteOutgoingEntryAndImage.outgoingEntry.totalImageCount, imageId)
      sendRemoteOutgoingFileCompleted(remoteOutgoingEntryAndImage).foreach { response =>
        pollRemoteBox()
      }

    case FetchFileFailed(remoteOutgoingEntry, exception) =>
      SbxLog.info("Box", s"Failed to fetch file from box ${box.name}: " + exception.getMessage)
      context.unbecome

    case HandlingFetchedFileFailed(remoteOutgoingEntry, exception) =>
      sendRemoteOutgoingFileFailed(FailedOutgoingEntry(remoteOutgoingEntry, exception.getMessage))
      SbxLog.error("Box", s"Failed to handle fetched file from box ${box.name}: " + exception.getMessage)
      context.unbecome

    case ReceiveTimeout =>
      log.error("Polling sequence timed out while fetching data")
      context.unbecome
  }

  def pollRemoteBox(): Unit = {
    context.become(waitForPollRemoteOutgoingState)

    sendPollRequestToRemoteBox
      .map(outgoingEntryAndImageMaybe => {
        updateBoxOnlineStatus(true)

        outgoingEntryAndImageMaybe match {
          case Some(outgoingEntryAndImage) =>
            self ! RemoteOutgoingEntryAndImageFound(outgoingEntryAndImage)
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

  def fetchFileForRemoteOutgoingEntry(remoteOutgoingEntryAndImage: OutgoingEntryAndImage): Unit =
    getRemoteOutgoingFile(remoteOutgoingEntryAndImage)
      .onComplete {

        case Success(response) =>
          if (response.status.intValue < 300) {
            val bytes = decompress(response.entity.data.toByteArray)
            val dataset = loadDataset(bytes, true)

            if (dataset == null)
              self ! HandlingFetchedFileFailed(remoteOutgoingEntryAndImage, new IllegalArgumentException("Dataset could not be read"))

            else
              anonymizationService.ask(ReverseAnonymization(dataset)).mapTo[Attributes]
                .onComplete {

                  case Success(reversedDataset) =>
                    val source = Source(SourceType.BOX, box.name, box.id)
                    storageService.ask(AddDataset(reversedDataset, source))
                      .onComplete {

                        case Success(DatasetAdded(image, source)) =>
                          self ! RemoteOutgoingFileFetched(remoteOutgoingEntryAndImage, image.id)
                        case Success(_) =>
                          self ! HandlingFetchedFileFailed(remoteOutgoingEntryAndImage, new Exception("Unexpected response when adding dataset"))
                        case Failure(exception) =>
                          self ! HandlingFetchedFileFailed(remoteOutgoingEntryAndImage, exception)
                      }

                  case Failure(exception) =>
                    self ! HandlingFetchedFileFailed(remoteOutgoingEntryAndImage, exception)
                }
          } else
            self ! FetchFileFailed(remoteOutgoingEntryAndImage, new RuntimeException("Server responded with status code " + response.status.intValue))

        case Failure(exception) =>
          self ! FetchFileFailed(remoteOutgoingEntryAndImage, exception)
      }

  def updateIncoming(remoteBoxId: Long, remoteBoxName: String, transactionId: Long, totalImageCount: Long, imageId: Long): Unit = {
    db.withSession { implicit session =>

      val existingEntry = boxDao.incomingEntryByTransactionId(remoteBoxId, transactionId)
        .getOrElse(boxDao.insertIncomingEntry(IncomingEntry(-1, remoteBoxId, remoteBoxName, transactionId, 0, totalImageCount, System.currentTimeMillis, TransactionStatus.WAITING)))
      val incomingEntry = existingEntry.incrementReceived.updateTimestamp.copy(totalImageCount = totalImageCount)
      boxDao.updateIncomingEntry(incomingEntry)
      boxDao.insertIncomingImage(IncomingImage(-1, incomingEntry.id, imageId))

      if (incomingEntry.receivedImageCount == incomingEntry.totalImageCount)
        db.withSession { implicit session =>
          boxDao.setOutgoingTransactionStatus(remoteBoxId, transactionId, TransactionStatus.FINISHED)
          SbxLog.info("Box", s"Received ${totalImageCount} files from box $remoteBoxName")
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
