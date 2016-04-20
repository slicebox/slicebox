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
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.util.ExceptionCatching

class BoxServiceActor(dbProps: DbProps, apiBaseURL: String, implicit val timeout: Timeout) extends Actor with Stash with ExceptionCatching {

  val log = Logging(context.system, this)

  val db = dbProps.db
  val boxDao = new BoxDAO(dbProps.driver)
  import dbProps.driver.simple.Session

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
      db.withTransaction { implicit session =>
        updatePollBoxesOnlineStatus
        updateTransactionsStatus
      }

    case ImageDeleted(imageId) =>
      removeImageFromBoxDb(imageId)

    case msg: BoxRequest =>

      catchAndReport {

        msg match {

          case CreateConnection(remoteBoxConnectionData) =>
            val token = UUID.randomUUID().toString
            val baseUrl = s"$apiBaseURL/transactions/$token"
            val name = remoteBoxConnectionData.name
            val box = addBoxToDb(Box(-1, name, token, baseUrl, BoxSendMethod.POLL, online = false))
            sender ! RemoteBoxAdded(box)

          case Connect(remoteBox) =>
            val box = pushBoxByBaseUrl(remoteBox.baseUrl) getOrElse {
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

          case GetBoxes =>
            val boxes = getBoxesFromDb
            sender ! Boxes(boxes)

          case GetBoxById(boxId) =>
            sender ! boxById(boxId)

          case GetBoxByToken(token) =>
            sender ! pollBoxByToken(token)

          case UpdateIncoming(box, outgoingTransactionId, sequenceNumber, totalImageCount, imageId, overwrite) =>
            val incomingTransactionWithStatus =
              db.withTransaction { implicit session =>
                val existingTransaction = boxDao.incomingTransactionByOutgoingTransactionId(box.id, outgoingTransactionId)
                  .getOrElse(boxDao.insertIncomingTransaction(IncomingTransaction(-1, box.id, box.name, outgoingTransactionId, 0, 0, totalImageCount, System.currentTimeMillis, System.currentTimeMillis, TransactionStatus.WAITING)))
                val addedImageCount = if (overwrite) existingTransaction.addedImageCount else existingTransaction.addedImageCount + 1
                val incomingTransaction = existingTransaction.copy(
                  receivedImageCount = sequenceNumber,
                  addedImageCount = addedImageCount,
                  totalImageCount = totalImageCount,
                  lastUpdated = System.currentTimeMillis,
                  status = TransactionStatus.PROCESSING)
                boxDao.updateIncomingTransaction(incomingTransaction)
                boxDao.incomingImageByIncomingTransactionIdAndSequenceNumber(incomingTransaction.id, sequenceNumber) match {
                  case Some(image) => boxDao.updateIncomingImage(image.copy(imageId = imageId))
                  case None        => boxDao.insertIncomingImage(IncomingImage(-1, incomingTransaction.id, imageId, sequenceNumber, overwrite))
                }

                val resultingTransaction =
                  if (sequenceNumber == totalImageCount) {
                    val nIncomingImages = boxDao.countIncomingImagesForIncomingTransactionId(incomingTransaction.id)
                    val status =
                      if (nIncomingImages == totalImageCount) {
                        SbxLog.info("Box", s"Received $totalImageCount files from box ${box.name}")
                        TransactionStatus.FINISHED
                      } else {
                        SbxLog.error("Box", s"Finished receiving $totalImageCount files from box ${box.name}, but only $nIncomingImages files can be found at this time.")
                        TransactionStatus.FAILED
                      }
                    boxDao.setIncomingTransactionStatus(incomingTransaction.id, status)
                    incomingTransaction.copy(status = status)
                  } else
                    incomingTransaction

                log.debug(s"Received pushed file and updated incoming transaction $resultingTransaction")
                resultingTransaction
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

          case MarkOutgoingTransactionAsFailed(box, failedTransactionImage) =>
            db.withSession { implicit session =>
              boxDao.setOutgoingTransactionStatus(failedTransactionImage.transactionImage.transaction.id, TransactionStatus.FAILED)
              SbxLog.error("Box", failedTransactionImage.message)
              sender ! OutgoingTransactionMarkedAsFailed
            }

          case GetIncomingTransactions =>
            sender ! IncomingTransactions(getIncomingTransactions)

          case GetOutgoingTransactions =>
            sender ! OutgoingTransactions(getOutgoingTransactions)

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

          case GetIncomingTransactionForImageId(imageId) =>
            sender ! incomingTransactionForImageId(imageId)

          case UpdateOutgoingTransaction(transactionImage) =>
            sender ! updateOutgoingTransaction(transactionImage)

          case SetOutgoingTransactionStatus(transaction, status) =>
            setOutgoingTransactionStatus(transaction, status)

          case SetIncomingTransactionStatus(boxId, transactionImage, status) =>
            setIncomingTransactionStatus(boxId, transactionImage, status)

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
    getBoxesFromDb foreach (box =>
      box.sendMethod match {
        case BoxSendMethod.PUSH =>
          maybeStartPushActor(box)
          maybeStartPollActor(box)
        case BoxSendMethod.POLL =>
          pollBoxesLastPollTimestamp(box.id) = 0
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
    db.withSession { implicit session =>
      if (boxDao.boxByName(box.name).isDefined)
        throw new IllegalArgumentException(s"A box with name ${box.name} already exists")
      boxDao.insertBox(box)
    }

  def boxById(boxId: Long): Option[Box] =
    db.withSession { implicit session =>
      boxDao.boxById(boxId)
    }

  def pushBoxByBaseUrl(baseUrl: String): Option[Box] =
    db.withSession { implicit session =>
      boxDao.pushBoxByBaseUrl(baseUrl)
    }

  def removeBoxFromDb(boxId: Long) =
    db.withSession { implicit session =>
      boxDao.removeBox(boxId)
    }

  def getBoxesFromDb: Seq[Box] =
    db.withSession { implicit session =>
      boxDao.listBoxes
    }

  def pollBoxByToken(token: String): Option[Box] =
    db.withSession { implicit session =>
      boxDao.pollBoxByToken(token)
    }

  def nextOutgoingTransactionImage(boxId: Long): Option[OutgoingTransactionImage] =
    db.withSession { implicit session =>
      boxDao.nextOutgoingTransactionImageForBoxId(boxId)
    }

  def updatePollBoxesOnlineStatus(implicit session: Session): Unit = {
    val now = System.currentTimeMillis

    pollBoxesLastPollTimestamp.foreach {
      case (boxId, lastPollTime) =>
        val online =
          if (now - lastPollTime < pollBoxOnlineStatusTimeoutMillis)
            true
          else
            false

        boxDao.updateBoxOnlineStatus(boxId, online)
    }
  }

  def updateBoxOnlineStatus(boxId: Long, online: Boolean): Unit =
    db.withSession { implicit session =>
      boxDao.updateBoxOnlineStatus(boxId, online)
    }

  def updateTransactionsStatus(implicit session: Session): Unit = {
    val now = System.currentTimeMillis

    getIncomingTransactionsInProcess.foreach { transaction =>
      if (now - transaction.lastUpdated < pollBoxOnlineStatusTimeoutMillis)
        boxDao.setIncomingTransactionStatus(transaction.id, TransactionStatus.WAITING)
    }

    getOutgoingTransactionsInProcess.foreach { transaction =>
      if (now - transaction.lastUpdated < pollBoxOnlineStatusTimeoutMillis)
        boxDao.setOutgoingTransactionStatus(transaction.id, TransactionStatus.WAITING)
    }
  }

  def setIncomingTransactionStatus(boxId: Long, transactionImage: OutgoingTransactionImage, status: TransactionStatus): Unit =
    db.withSession { implicit session =>
      boxDao.incomingTransactionByOutgoingTransactionId(boxId, transactionImage.transaction.id).foreach(incomingTransaction =>
        boxDao.setIncomingTransactionStatus(incomingTransaction.id, status))
    }

  def addImagesToOutgoing(boxId: Long, boxName: String, imageTagValuesSeq: Seq[ImageTagValues]) =
    db.withSession { implicit session =>
      val outgoingTransaction = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, boxId, boxName, 0, imageTagValuesSeq.length, System.currentTimeMillis, System.currentTimeMillis, TransactionStatus.WAITING))
      imageTagValuesSeq.zipWithIndex.foreach {
        case (imageTagValues, index) =>
          val sequenceNumber = index + 1
          val outgoingImage = boxDao.insertOutgoingImage(OutgoingImage(-1, outgoingTransaction.id, imageTagValues.imageId, sequenceNumber, sent = false))
          imageTagValues.tagValues.foreach { tagValue =>
            boxDao.insertOutgoingTagValue(OutgoingTagValue(-1, outgoingImage.id, tagValue))
          }
      }
    }

  def outgoingTransactionImageById(boxId: Long, outgoingTransactionId: Long, outgoingImageId: Long): Option[OutgoingTransactionImage] =
    db.withSession { implicit session =>
      boxDao.outgoingTransactionImageByOutgoingTransactionIdAndOutgoingImageId(boxId, outgoingTransactionId, outgoingImageId)
    }

  def removeIncomingTransactionFromDb(incomingTransactionId: Long) =
    db.withSession { implicit session =>
      boxDao.removeIncomingTransaction(incomingTransactionId)
    }

  def removeOutgoingTransactionFromDb(outgoingTransactionId: Long) =
    db.withSession { implicit session =>
      boxDao.removeOutgoingTransaction(outgoingTransactionId)
    }

  def getIncomingTransactions =
    db.withSession { implicit session =>
      boxDao.listIncomingTransactions
    }

  def getOutgoingTransactions =
    db.withSession { implicit session =>
      boxDao.listOutgoingTransactions
    }

  def getIncomingTransactionsInProcess =
    db.withSession { implicit session =>
      boxDao.listIncomingTransactionsInProcess
    }

  def getOutgoingTransactionsInProcess =
    db.withSession { implicit session =>
      boxDao.listOutgoingTransactionsInProcess
    }

  def getIncomingImagesByIncomingTransactionId(incomingTransactionId: Long) =
    db.withSession { implicit session =>
      boxDao.listIncomingImagesForIncomingTransactionId(incomingTransactionId)
    }

  def getOutgoingImagesByOutgoingTransactionId(outgoingTransactionId: Long) =
    db.withSession { implicit session =>
      boxDao.listOutgoingImagesForOutgoingTransactionId(outgoingTransactionId)
    }

  def tagValuesForOutgoingTransactionImage(transactionImage: OutgoingTransactionImage): Seq[OutgoingTagValue] =
    db.withSession { implicit session =>
      boxDao.tagValuesByOutgoingTransactionImage(transactionImage.transaction.id, transactionImage.image.id)
    }

  def incomingTransactionForImageId(imageId: Long): Option[IncomingTransaction] =
    db.withSession { implicit session =>
      boxDao.incomingTransactionByImageId(imageId)
    }

  def outgoingImageIdsForTransactionId(transactionId: Long): Seq[Long] =
    db.withSession { implicit session =>
      boxDao.outgoingImagesByOutgoingTransactionId(transactionId).map(_.imageId)
    }

  def removeImageFromBoxDb(imageId: Long) =
    db.withSession { implicit session =>
      boxDao.removeOutgoingImagesForImageId(imageId)
      boxDao.removeIncomingImagesForImageId(imageId)
    }

  def nextOutgoingTransaction(boxId: Long): Option[OutgoingTransactionImage] =
    db.withSession { implicit session =>
      boxDao.nextOutgoingTransactionImageForBoxId(boxId)
    }

  def tagValuesForImageIdAndTransactionId(transactionImage: OutgoingTransactionImage): Seq[OutgoingTagValue] =
    db.withSession { implicit session =>
      boxDao.tagValuesByOutgoingTransactionImage(transactionImage.transaction.id, transactionImage.image.id)
    }

  def setOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus) =
    db.withSession { implicit session =>
      boxDao.setOutgoingTransactionStatus(transaction.id, status)
    }

  def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage): OutgoingTransaction =
    db.withTransaction { implicit session =>
      val updatedTransactionImage = transactionImage.image.copy(sent = true)
      val updatedTransaction = transactionImage.transaction.copy(
        sentImageCount = transactionImage.image.sequenceNumber,
        lastUpdated = System.currentTimeMillis,
        status = TransactionStatus.PROCESSING)
      boxDao.updateOutgoingTransaction(updatedTransaction)
      boxDao.updateOutgoingImage(updatedTransactionImage)

      if (updatedTransaction.sentImageCount == updatedTransaction.totalImageCount) {
        boxDao.setOutgoingTransactionStatus(updatedTransaction.id, TransactionStatus.FINISHED)
      }
      log.debug(s"Marked outgoing transaction image $updatedTransactionImage as sent")
      updatedTransaction
    }

}

object BoxServiceActor {
  def props(dbProps: DbProps, apiBaseURL: String, timeout: Timeout): Props = Props(new BoxServiceActor(dbProps, apiBaseURL, timeout))
}
