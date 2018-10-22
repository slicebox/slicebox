/*
 * Copyright 2014 Lars Edenbrandt
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

import java.util.UUID

import akka.actor.{Actor, ActorSystem, Cancellable, PoisonPill, Props, Stash}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.PipeToSupport
import akka.stream.Materializer
import akka.util.Timeout
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.storage.StorageService
import se.nimsa.sbx.util.SbxExtensions._
import se.nimsa.sbx.util.SequentialPipeToSupport

import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}

class BoxServiceActor(boxDao: BoxDAO, apiBaseURL: String, storage: StorageService)(implicit val materializer: Materializer, timeout: Timeout) extends Actor with Stash with PipeToSupport with SequentialPipeToSupport {

  val log = Logging(context.system, this)

  val pollBoxOnlineStatusTimeoutMillis: Long = 15000
  val pollBoxesLastPollTimestamp = mutable.Map.empty[Long, Long] // box id to timestamp

  implicit val system: ActorSystem = context.system
  implicit val ec: ExecutionContextExecutor = context.dispatcher

  setupBoxes()

  val pollBoxesOnlineStatusSchedule: Cancellable =
    context.system.scheduler.schedule(100.milliseconds, 7.seconds) {
      self ! UpdateStatusForBoxesAndTransactions
    }

  log.info("Box service started")

  override def preStart(): Unit =
    context.system.eventStream.subscribe(context.self, classOf[ImagesDeleted])

  override def postStop(): Unit =
    pollBoxesOnlineStatusSchedule.cancel()

  def receive = LoggingReceive {

    case UpdateStatusForBoxesAndTransactions =>
      val now = System.currentTimeMillis()
      boxDao.updateStatusForBoxesAndTransactions(now, pollBoxesLastPollTimestamp.toMap, pollBoxOnlineStatusTimeoutMillis)

    case ImagesDeleted(imageIds) =>
      boxDao.removeOutgoingImagesForImageIds(imageIds) zip boxDao.removeIncomingImagesForImageIds(imageIds)

    case msg: BoxRequest =>

      msg match {

        case CreateConnection(remoteBoxConnectionData) =>
          boxDao.boxByName(remoteBoxConnectionData.name).flatMap {
            case Some(existingBox) if existingBox.sendMethod == BoxSendMethod.PUSH =>
              Future.failed(new IllegalArgumentException(s"A box with name ${existingBox.name} but with a different type already exists"))
            case Some(existingBox) => Future.successful(existingBox)
            case None =>
              val token = UUID.randomUUID().toString
              val baseUrl = s"$apiBaseURL/transactions/$token"
              val name = remoteBoxConnectionData.name
              boxDao.insertBox(Box(-1, name, token, baseUrl, BoxSendMethod.POLL, online = false))
          }.map(RemoteBoxAdded).pipeSequentiallyTo(sender)

        case Connect(remoteBox) =>
          boxDao.boxByName(remoteBox.name).flatMap {
            case Some(existingBox) if existingBox.sendMethod == BoxSendMethod.POLL || existingBox.baseUrl != remoteBox.baseUrl =>
              Future.failed(new IllegalArgumentException(s"A box with name ${existingBox.name} but with a different url and/or type already exists"))
            case Some(existingBox) => Future.successful(existingBox)
            case None =>
              val token = baseUrlToToken(remoteBox.baseUrl)
              boxDao.insertBox(Box(-1, remoteBox.name, token, remoteBox.baseUrl, BoxSendMethod.PUSH, online = false))
          }.map { box =>
            maybeStartPushActor(box)
            maybeStartPollActor(box)
            RemoteBoxAdded(box)
          }.pipeSequentiallyTo(sender)

        case RemoveBox(boxId) =>
          boxDao.boxById(boxId).map(_.foreach(box => {
            context.child(pushActorName(box))
              .foreach(_ ! PoisonPill)
            context.child(pollActorName(box))
              .foreach(_ ! PoisonPill)
          }))
            .map(_ => boxDao.removeBox(boxId))
            .map(_ => BoxRemoved(boxId))
            .pipeSequentiallyTo(sender)

        case GetBoxes(startIndex, count) =>
          boxDao.listBoxes(startIndex, count).map(Boxes).pipeTo(sender)

        case GetBoxById(boxId) =>
          boxDao.boxById(boxId).pipeTo(sender)

        case GetBoxByToken(token) =>
          boxDao.pollBoxByToken(token).pipeTo(sender)

        case UpdateIncoming(box, outgoingTransactionId, sequenceNumber, totalImageCount, imageIdMaybe, added) =>
          val futureIncomingTransactionWithStatus = boxDao.updateIncoming(box, outgoingTransactionId, sequenceNumber, totalImageCount, imageIdMaybe, added)

          futureIncomingTransactionWithStatus.foreach { incomingTransactionWithStatus =>
            log.debug(s"Updated incoming transaction $incomingTransactionWithStatus")
          }

          futureIncomingTransactionWithStatus.map(IncomingUpdated).pipeSequentiallyTo(sender)

        case PollOutgoing(box, n) =>
          if (box.sendMethod == BoxSendMethod.POLL) pollBoxesLastPollTimestamp(box.id) = System.currentTimeMillis
          val futureOutgoingTransactionImages = boxDao.nextOutgoingTransactionImagesForBoxId(box.id, n)

          futureOutgoingTransactionImages.foreach(outgoingTransactionImages =>
            log.debug(s"Received poll request, responding with outgoing transaction images $outgoingTransactionImages"))

          futureOutgoingTransactionImages.pipeTo(sender)

        case SendToRemoteBox(box, imageTagValuesSeq) =>
          SbxLog.info("Box", s"Sending ${imageTagValuesSeq.length} images to box ${box.name}")
          boxDao.addImagesToOutgoing(box.id, box.name, imageTagValuesSeq)
            .map(_ => ImagesAddedToOutgoing(box.id, imageTagValuesSeq.map(_.imageId)))
            .pipeSequentiallyTo(sender)

        case GetOutgoingTransactionImage(box, outgoingTransactionId, outgoingImageId) =>
          val futureOutgoingTransactionImage = boxDao.outgoingTransactionImageByOutgoingTransactionIdAndOutgoingImageId(box.id, outgoingTransactionId, outgoingImageId)

          futureOutgoingTransactionImage.foreach(outgoingTransactionImage =>
            log.debug(s"Received image file request, responding with outgoing transaction image $outgoingTransactionImage"))

          futureOutgoingTransactionImage.pipeTo(sender)

        case GetOutgoingTransactionsForBox(box) =>
          boxDao.listPendingOutgoingTransactionsForBox(box.id).pipeTo(sender)

        case MarkOutgoingImageAsSent(box, transactionImage) =>
          if (box.sendMethod == BoxSendMethod.POLL) pollBoxesLastPollTimestamp(box.id) = System.currentTimeMillis
          boxDao.outgoingTransactionImageByOutgoingTransactionIdAndOutgoingImageId(box.id, transactionImage.transaction.id, transactionImage.image.id)
            .map { dbTransactionImageMaybe =>
              dbTransactionImageMaybe
                .map { dbTransactionImage =>
                  updateOutgoingTransactionOnImageSent(dbTransactionImage, math.min(dbTransactionImage.transaction.totalImageCount, dbTransactionImage.transaction.sentImageCount + 1))
                    .flatMap(updatedTransactionImage => maybeFinalizeOutgoingTransaction(box, updatedTransactionImage.transaction))
                }
            }
            .unwrap
            .map(_ => OutgoingImageMarkedAsSent).pipeSequentiallyTo(sender)

        case MarkOutgoingTransactionAsFailed(_, failedTransactionImage) =>
          SbxLog.error("Box", failedTransactionImage.message)

          boxDao.setOutgoingTransactionStatus(failedTransactionImage.transactionImage.transaction.id, TransactionStatus.FAILED)
            .map(_ => OutgoingTransactionMarkedAsFailed)
            .pipeSequentiallyTo(sender)

        case GetIncomingTransactionStatus(box, transactionId) =>
          boxDao
            .incomingTransactionByOutgoingTransactionId(box.id, transactionId)
            .map(_.map(_.status))
            .pipeTo(sender)

        case GetIncomingTransactions(startIndex, count) =>
          boxDao.listIncomingTransactions(startIndex, count).map(IncomingTransactions).pipeTo(sender)

        case GetOutgoingTransactions(startIndex, count) =>
          boxDao.listOutgoingTransactions(startIndex, count).map(OutgoingTransactions).pipeTo(sender)

        case GetImageIdsForIncomingTransaction(incomingTransactionId) =>
          boxDao.listIncomingImagesForIncomingTransactionId(incomingTransactionId).map(_.map(_.imageId)).pipeTo(sender)

        case GetImageIdsForOutgoingTransaction(outgoingTransactionId) =>
          boxDao.listOutgoingImagesForOutgoingTransactionId(outgoingTransactionId).map(_.map(_.imageId)).pipeTo(sender)

        case RemoveOutgoingTransaction(outgoingTransactionId) =>
          boxDao.removeOutgoingTransaction(outgoingTransactionId).map(_ => OutgoingTransactionRemoved(outgoingTransactionId))
            .pipeSequentiallyTo(sender)

        case RemoveIncomingTransaction(incomingTransactionId) =>
          boxDao.removeIncomingTransaction(incomingTransactionId).map(_ => IncomingTransactionRemoved(incomingTransactionId))
            .pipeSequentiallyTo(sender)

        case GetOutgoingTagValues(transactionImage) =>
          boxDao.tagValuesByOutgoingTransactionImage(transactionImage.transaction.id, transactionImage.image.id)
            .pipeTo(sender)

        case UpdateOutgoingTransaction(transactionImage, sentImageCount) =>
          updateOutgoingTransactionOnImageSent(transactionImage, sentImageCount).pipeSequentiallyTo(sender)

        case SetOutgoingTransactionStatus(transaction, status) =>
          boxDao.setOutgoingTransactionStatus(transaction.id, status).map(_ => OutgoingTransactionStatusUpdated)
            .pipeSequentiallyTo(sender)

        case SetIncomingTransactionStatus(box, transactionId, status) =>
          boxDao.incomingTransactionByOutgoingTransactionId(box.id, transactionId)
            .map(_.map { incomingTransaction =>
              if (status == TransactionStatus.FINISHED)
                boxDao.updateIncomingTransaction(incomingTransaction.copy(status = status, receivedImageCount = incomingTransaction.totalImageCount))
                  .flatMap { _ =>
                    boxDao.outgoingTransactionByTransactionId(box.id, transactionId)
                      .map(_.map(outgoingTransaction => maybeFinalizeOutgoingTransaction(box, outgoingTransaction)))
                      .unwrap
                  }
                  .map(_ => IncomingTransactionStatusUpdated)
              else
                boxDao.updateIncomingTransaction(incomingTransaction.copy(status = status))
                  .map(_ => IncomingTransactionStatusUpdated)
            })
            .unwrap
            .pipeSequentiallyTo(sender)

        case UpdateBoxOnlineStatus(boxId, online) =>
          boxDao.updateBoxOnlineStatus(boxId, online)
            .pipeSequentiallyTo(sender)
      }

  }

  def baseUrlToToken(url: String): String =
    try {
      val trimmedUrl = url.trim.stripSuffix("/")
      val token = trimmedUrl.substring(trimmedUrl.lastIndexOf("/") + 1)
      // see if the UUID class accepts the string as a valid token, throw exception if not
      UUID.fromString(token)
      token
    } catch {
      case e: Exception => throw new IllegalArgumentException("Malformed box base url: " + url, e)
    }

  def setupBoxes(): Future[Unit] =
    boxDao.listBoxes(0, 10000000).map(_ foreach (box =>
      box.sendMethod match {
        case BoxSendMethod.PUSH =>
          maybeStartPushActor(box)
          maybeStartPollActor(box)
        case BoxSendMethod.POLL =>
          pollBoxesLastPollTimestamp(box.id) = 0
        case _ =>
      }))

  def maybeStartPushActor(box: Box): Unit = {
    val actorName = pushActorName(box)
    if (context.child(actorName).isEmpty)
      context.actorOf(BoxPushActor.props(box, storage), actorName)
  }

  def maybeStartPollActor(box: Box): Unit = {
    val actorName = pollActorName(box)
    if (context.child(actorName).isEmpty)
      context.actorOf(BoxPollActor.props(box, storage), actorName)
  }

  def pushActorName(box: Box): String = BoxSendMethod.PUSH + "-" + box.id.toString

  def pollActorName(box: Box): String = BoxSendMethod.POLL + "-" + box.id.toString

  def maybeFinalizeOutgoingTransaction(box: Box, transaction: OutgoingTransaction): Future[Unit] = {
    if (transaction.sentImageCount >= transaction.totalImageCount && transaction.status != TransactionStatus.FINISHED) {
      boxDao.outgoingImagesByOutgoingTransactionId(transaction.id).map(_.map(_.imageId))
        .map { imageIds =>
          context.system.eventStream.publish(ImagesSent(Destination(DestinationType.BOX, box.name, box.id), imageIds))
          SbxLog.info("Box", s"Finished sending ${transaction.totalImageCount} images to box ${box.name}")
        }
        .flatMap(_ => boxDao.updateOutgoingTransaction(transaction.copy(status = TransactionStatus.FINISHED)))
        .map(_ => Unit)
    } else Future.successful(Unit)
  }

  def updateOutgoingTransactionOnImageSent(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] = {
    val updatedTransactionImage = transactionImage.update(sentImageCount)

    boxDao.updateOutgoingTransactionImage(updatedTransactionImage.transaction, updatedTransactionImage.image).map { _ =>
      log.debug(s"Marked outgoing transaction and image $updatedTransactionImage as sent")
      updatedTransactionImage
    }
  }

}

object BoxServiceActor {
  def props(boxDao: BoxDAO, apiBaseURL: String, storage: StorageService)(implicit materializer: Materializer, timeout: Timeout): Props = Props(new BoxServiceActor(boxDao, apiBaseURL, storage))
}
