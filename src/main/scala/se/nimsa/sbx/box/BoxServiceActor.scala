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

import java.util.UUID

import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import akka.actor.Actor
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.Stash
import akka.event.Logging
import akka.event.LoggingReceive
import akka.util.Timeout
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.util.FutureUtil.await

class BoxServiceActor(boxDao: BoxDAO, apiBaseURL: String)(implicit val timeout: Timeout) extends Actor with Stash with ExceptionCatching {

  val log = Logging(context.system, this)

  val pollBoxOnlineStatusTimeoutMillis: Long = 15000
  val pollBoxesLastPollTimestamp = mutable.Map.empty[Long, Long] // box id to timestamp

  implicit val system = context.system
  implicit val ec = context.dispatcher

  setupBoxes()

  val pollBoxesOnlineStatusSchedule = context.system.scheduler.schedule(100.milliseconds, 7.seconds) {
    self ! UpdateStatusForBoxesAndTransactions
  }

  log.info("Box service started")

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[ImageDeleted])
  }

  override def postStop() =
    pollBoxesOnlineStatusSchedule.cancel()

  def receive = LoggingReceive {

    case UpdateStatusForBoxesAndTransactions =>
      val now = System.currentTimeMillis()
      await(boxDao.updateStatusForBoxesAndTransactions(now, pollBoxesLastPollTimestamp.toMap, pollBoxOnlineStatusTimeoutMillis))

    case ImageDeleted(imageId) =>
      removeImageFromBoxDb(imageId)

    case msg: BoxRequest =>

      catchAndReport {

        msg match {

          case CreateConnection(remoteBoxConnectionData) =>
            val box = boxByName(remoteBoxConnectionData.name) match {
              case Some(existingBox) if existingBox.sendMethod == BoxSendMethod.PUSH =>
                throw new IllegalArgumentException(s"A box with name ${existingBox.name} but with a different type already exists")
              case Some(existingBox) => existingBox
              case None =>
                val token = UUID.randomUUID().toString
                val baseUrl = s"$apiBaseURL/transactions/$token"
                val name = remoteBoxConnectionData.name
                addBoxToDb(Box(-1, name, token, baseUrl, BoxSendMethod.POLL, online = false))
            }
            sender ! RemoteBoxAdded(box)

          case Connect(remoteBox) =>
            val box = boxByName(remoteBox.name) match {
              case Some(existingBox) if existingBox.sendMethod == BoxSendMethod.POLL || existingBox.baseUrl != remoteBox.baseUrl =>
                throw new IllegalArgumentException(s"A box with name ${existingBox.name} but with a different url and/or type already exists")
              case Some(existingBox) => existingBox
              case None =>
                val token = baseUrlToToken(remoteBox.baseUrl)
                addBoxToDb(Box(-1, remoteBox.name, token, remoteBox.baseUrl, BoxSendMethod.PUSH, online = false))
            }
            maybeStartPushActor(box)
            maybeStartPollActor(box)
            sender ! RemoteBoxAdded(box)

          case RemoveBox(boxId) =>
            boxById(boxId).foreach(box => {
              context.child(pushActorName(box))
                .foreach(_ ! PoisonPill)
              context.child(pollActorName(box))
                .foreach(_ ! PoisonPill)
            })
            removeBoxFromDb(boxId)
            sender ! BoxRemoved(boxId)

          case GetBoxes(startIndex, count) =>
            val boxes = getBoxesFromDb(startIndex, count)
            sender ! Boxes(boxes)

          case GetBoxById(boxId) =>
            sender ! boxById(boxId)

          case GetBoxByToken(token) =>
            sender ! pollBoxByToken(token)

          case UpdateIncoming(box, outgoingTransactionId, sequenceNumber, totalImageCount, imageId, overwrite) =>
            val incomingTransactionWithStatus =
              await(boxDao.updateIncoming(box, outgoingTransactionId, sequenceNumber, totalImageCount, imageId, overwrite))

            incomingTransactionWithStatus.status match {
              case TransactionStatus.FINISHED =>
                SbxLog.info("Box", s"Received $totalImageCount files from box ${box.name}")
              case TransactionStatus.FAILED =>
                val nIncomingImages = await(boxDao.countIncomingImagesForIncomingTransactionId(incomingTransactionWithStatus.id))
                SbxLog.error("Box", s"Finished receiving $totalImageCount files from box ${box.name}, but only $nIncomingImages files can be found at this time.")
              case _ =>
                log.debug(s"Received pushed file and updated incoming transaction $incomingTransactionWithStatus")
            }

            sender ! IncomingUpdated(incomingTransactionWithStatus)

          case PollOutgoing(box) =>
            pollBoxesLastPollTimestamp(box.id) = System.currentTimeMillis
            val outgoingTransaction = nextOutgoingTransactionImage(box.id)
            log.debug(s"Received poll request, responding with outgoing transaction $outgoingTransaction")
            sender ! outgoingTransaction

          case SendToRemoteBox(box, imageTagValuesSeq) =>
            SbxLog.info("Box", s"Sending ${imageTagValuesSeq.length} images to box ${box.name}")
            addImagesToOutgoing(box.id, box.name, imageTagValuesSeq)
            sender ! ImagesAddedToOutgoing(box.id, imageTagValuesSeq.map(_.imageId))

          case GetOutgoingTransactionImage(box, outgoingTransactionId, outgoingImageId) =>
            val outgoingTransactionImage = outgoingTransactionImageById(box.id, outgoingTransactionId, outgoingImageId)
            log.debug(s"Received image file request, responding with outgoing transaction image $outgoingTransactionImage")
            sender ! outgoingTransactionImage

          case GetNextOutgoingTransactionImage(boxId) =>
            sender ! nextOutgoingTransactionImage(boxId)

          case GetOutgoingImageIdsForTransaction(transaction) =>
            sender ! outgoingImageIdsForTransactionId(transaction.id)

          case MarkOutgoingImageAsSent(box, transactionImage) =>
            val updatedTransaction = updateOutgoingTransaction(transactionImage)

            if (updatedTransaction.sentImageCount == updatedTransaction.totalImageCount) {
              context.system.eventStream.publish(ImagesSent(Destination(DestinationType.BOX, box.name, box.id), outgoingImageIdsForTransactionId(updatedTransaction.id)))
              SbxLog.info("Box", s"Finished sending ${updatedTransaction.totalImageCount} images to box ${box.name}")
            }
            sender ! OutgoingImageMarkedAsSent

          case MarkOutgoingTransactionAsFailed(_, failedTransactionImage) =>
            boxDao.setOutgoingTransactionStatus(failedTransactionImage.transactionImage.transaction.id, TransactionStatus.FAILED)
            SbxLog.error("Box", failedTransactionImage.message)
            sender ! OutgoingTransactionMarkedAsFailed

          case GetIncomingTransactionStatus(box, transactionId) =>
            sender ! getIncomingTransactionStatus(box, transactionId)

          case GetIncomingTransactions(startIndex, count) =>
            sender ! IncomingTransactions(getIncomingTransactions(startIndex, count))

          case GetOutgoingTransactions(startIndex, count) =>
            sender ! OutgoingTransactions(getOutgoingTransactions(startIndex, count))

          case GetImageIdsForIncomingTransaction(incomingTransactionId) =>
            val imageIds = getIncomingImagesByIncomingTransactionId(incomingTransactionId).map(_.imageId)
            sender ! imageIds

          case GetImageIdsForOutgoingTransaction(outgoingTransactionId) =>
            val imageIds = getOutgoingImagesByOutgoingTransactionId(outgoingTransactionId).map(_.imageId)
            sender ! imageIds

          case RemoveOutgoingTransaction(outgoingTransactionId) =>
            removeOutgoingTransactionFromDb(outgoingTransactionId)
            sender ! OutgoingTransactionRemoved(outgoingTransactionId)

          case RemoveIncomingTransaction(incomingTransactionId) =>
            removeIncomingTransactionFromDb(incomingTransactionId)
            sender ! IncomingTransactionRemoved(incomingTransactionId)

          case GetOutgoingTagValues(transactionImage) =>
            sender ! tagValuesForOutgoingTransactionImage(transactionImage)

          case UpdateOutgoingTransaction(transactionImage) =>
            sender ! updateOutgoingTransaction(transactionImage)

          case SetOutgoingTransactionStatus(transaction, status) =>
            setOutgoingTransactionStatus(transaction, status)
            sender ! OutgoingTransactionStatusUpdated

          case SetIncomingTransactionStatus(boxId, transactionId, status) =>
            sender ! setIncomingTransactionStatus(boxId, transactionId, status).map { _ =>
              IncomingTransactionStatusUpdated
            }

          case UpdateBoxOnlineStatus(boxId, online) =>
            updateBoxOnlineStatus(boxId, online)
        }

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

  def setupBoxes(): Unit =
    getBoxesFromDb(0, 10000000) foreach (box =>
      box.sendMethod match {
        case BoxSendMethod.PUSH =>
          maybeStartPushActor(box)
          maybeStartPollActor(box)
        case BoxSendMethod.POLL =>
          pollBoxesLastPollTimestamp(box.id) = 0
        case _ =>
      })

  def maybeStartPushActor(box: Box): Unit = {
    val actorName = pushActorName(box)
    if (context.child(actorName).isEmpty)
      context.actorOf(BoxPushActor.props(box, timeout), actorName)
  }

  def maybeStartPollActor(box: Box): Unit = {
    val actorName = pollActorName(box)
    if (context.child(actorName).isEmpty)
      context.actorOf(BoxPollActor.props(box, timeout), actorName)
  }

  def pushActorName(box: Box): String = BoxSendMethod.PUSH + "-" + box.id.toString

  def pollActorName(box: Box): String = BoxSendMethod.POLL + "-" + box.id.toString

  def addBoxToDb(box: Box): Box =
    await(boxDao.insertBox(box))

  def boxById(boxId: Long): Option[Box] =
    await(boxDao.boxById(boxId))

  def boxByName(name: String): Option[Box] =
    await(boxDao.boxByName(name))

  def removeBoxFromDb(boxId: Long) =
    await(boxDao.removeBox(boxId))

  def getBoxesFromDb(startIndex: Long, count: Long): Seq[Box] =
    await(boxDao.listBoxes(startIndex, count))

  def pollBoxByToken(token: String): Option[Box] =
    await(boxDao.pollBoxByToken(token))

  def nextOutgoingTransactionImage(boxId: Long): Option[OutgoingTransactionImage] =
    await(boxDao.nextOutgoingTransactionImageForBoxId(boxId))

  def updatePollBoxesOnlineStatus(): Unit = {
    val now = System.currentTimeMillis

    pollBoxesLastPollTimestamp.foreach {
      case (boxId, lastPollTime) =>
        val online = (now - lastPollTime) < pollBoxOnlineStatusTimeoutMillis
        await(boxDao.updateBoxOnlineStatus(boxId, online))
    }
  }

  def updateBoxOnlineStatus(boxId: Long, online: Boolean): Unit =
    await(boxDao.updateBoxOnlineStatus(boxId, online))

  def updateTransactionsStatus(): Unit = {
    val now = System.currentTimeMillis

    getIncomingTransactionsInProcess.foreach { transaction =>
      if ((now - transaction.updated) > pollBoxOnlineStatusTimeoutMillis)
        await(boxDao.setIncomingTransactionStatus(transaction.id, TransactionStatus.WAITING))
    }

    getOutgoingTransactionsInProcess.foreach { transaction =>
      if ((now - transaction.updated) > pollBoxOnlineStatusTimeoutMillis)
        await(boxDao.setOutgoingTransactionStatus(transaction.id, TransactionStatus.WAITING))
    }
  }

  def setIncomingTransactionStatus(boxId: Long, transactionId: Long, status: TransactionStatus): Option[Unit] =
    await(boxDao.incomingTransactionByOutgoingTransactionId(boxId, transactionId)).map { incomingTransaction =>
      await(boxDao.setIncomingTransactionStatus(incomingTransaction.id, status))
    }

  def addImagesToOutgoing(boxId: Long, boxName: String, imageTagValuesSeq: Seq[ImageTagValues]) = {
    val outgoingTransaction = await(boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, boxId, boxName, 0, imageTagValuesSeq.length, System.currentTimeMillis, System.currentTimeMillis, TransactionStatus.WAITING)))
    imageTagValuesSeq.zipWithIndex.foreach {
      case (imageTagValues, index) =>
        val sequenceNumber = index + 1
        val outgoingImage = await(boxDao.insertOutgoingImage(OutgoingImage(-1, outgoingTransaction.id, imageTagValues.imageId, sequenceNumber, sent = false)))
        imageTagValues.tagValues.foreach { tagValue =>
          await(boxDao.insertOutgoingTagValue(OutgoingTagValue(-1, outgoingImage.id, tagValue)))
        }
    }
  }

  def outgoingTransactionImageById(boxId: Long, outgoingTransactionId: Long, outgoingImageId: Long): Option[OutgoingTransactionImage] =
    await(boxDao.outgoingTransactionImageByOutgoingTransactionIdAndOutgoingImageId(boxId, outgoingTransactionId, outgoingImageId))

  def removeIncomingTransactionFromDb(incomingTransactionId: Long) =
    await(boxDao.removeIncomingTransaction(incomingTransactionId))

  def removeOutgoingTransactionFromDb(outgoingTransactionId: Long) =
    await(boxDao.removeOutgoingTransaction(outgoingTransactionId))

  def getIncomingTransactions(startIndex: Long, count: Long) =
    await(boxDao.listIncomingTransactions(startIndex, count))

  def getIncomingTransactionStatus(box: Box, transactionId: Long): Option[TransactionStatus] =
    await(boxDao.incomingTransactionByOutgoingTransactionId(box.id, transactionId)).map(_.status)

  def getOutgoingTransactions(startIndex: Long, count: Long) =
    await(boxDao.listOutgoingTransactions(startIndex, count))

  def getIncomingTransactionsInProcess =
    await(boxDao.listIncomingTransactionsInProcess)

  def getOutgoingTransactionsInProcess =
    await(boxDao.listOutgoingTransactionsInProcess)

  def getIncomingImagesByIncomingTransactionId(incomingTransactionId: Long) =
    await(boxDao.listIncomingImagesForIncomingTransactionId(incomingTransactionId))

  def getOutgoingImagesByOutgoingTransactionId(outgoingTransactionId: Long) =
    await(boxDao.listOutgoingImagesForOutgoingTransactionId(outgoingTransactionId))

  def tagValuesForOutgoingTransactionImage(transactionImage: OutgoingTransactionImage): Seq[OutgoingTagValue] =
    await(boxDao.tagValuesByOutgoingTransactionImage(transactionImage.transaction.id, transactionImage.image.id))

  def outgoingImageIdsForTransactionId(transactionId: Long): Seq[Long] =
    await(boxDao.outgoingImagesByOutgoingTransactionId(transactionId)).map(_.imageId)

  def removeImageFromBoxDb(imageId: Long) = {
    await(boxDao.removeOutgoingImagesForImageId(imageId))
    await(boxDao.removeIncomingImagesForImageId(imageId))
  }

  def nextOutgoingTransaction(boxId: Long): Option[OutgoingTransactionImage] =
    await(boxDao.nextOutgoingTransactionImageForBoxId(boxId))

  def tagValuesForImageIdAndTransactionId(transactionImage: OutgoingTransactionImage): Seq[OutgoingTagValue] =
    await(boxDao.tagValuesByOutgoingTransactionImage(transactionImage.transaction.id, transactionImage.image.id))

  def setOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus) =
    await(boxDao.setOutgoingTransactionStatus(transaction.id, status))

  def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage): OutgoingTransaction = {
    val updatedTransactionImage = transactionImage.image.copy(sent = true)
    val updatedTransaction = transactionImage.transaction.copy(
      sentImageCount = transactionImage.image.sequenceNumber,
      updated = System.currentTimeMillis,
      status = TransactionStatus.PROCESSING)

    await {
      boxDao.updateOutgoingTransaction(updatedTransaction, updatedTransactionImage).map { _ =>
        log.debug(s"Marked outgoing transaction image $updatedTransactionImage as sent")
        updatedTransaction
      }
    }
  }

}

object BoxServiceActor {
  def props(boxDao: BoxDAO, apiBaseURL: String, timeout: Timeout): Props = Props(new BoxServiceActor(boxDao, apiBaseURL)(timeout))
}
