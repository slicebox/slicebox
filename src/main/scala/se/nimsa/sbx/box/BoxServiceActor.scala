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

import java.util.Date
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.math.abs

import akka.actor.Actor
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.Stash
import akka.event.Logging
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.util.ExceptionCatching

class BoxServiceActor(dbProps: DbProps, apiBaseURL: String, implicit val timeout: Timeout) extends Actor with Stash with ExceptionCatching {

  case object UpdatePollBoxesOnlineStatus

  val log = Logging(context.system, this)

  val db = dbProps.db
  val boxDao = new BoxDAO(dbProps.driver)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val pollBoxOnlineStatusTimeoutMillis: Long = 15000
  val pollBoxesLastPollTimestamp = mutable.Map.empty[Long, Long] // box id to timestamp

  val metaDataService = context.actorSelection("../MetaDataService")

  setupBoxes()

  val pollBoxesOnlineStatusSchedule = system.scheduler.schedule(100.milliseconds, 5.seconds) {
    self ! UpdatePollBoxesOnlineStatus
  }

  log.info("Box service started")

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[ImageDeleted])
  }

  override def postStop() =
    pollBoxesOnlineStatusSchedule.cancel()

  def receive = LoggingReceive {

    case UpdatePollBoxesOnlineStatus =>
      updatePollBoxesOnlineStatus()

    case ImageDeleted(imageId) =>
      removeImageFromBoxDb(imageId)

    case msg: BoxRequest =>

      catchAndReport {

        msg match {

          case CreateConnection(remoteBoxConnectionData) =>
            val token = UUID.randomUUID().toString()
            val baseUrl = s"$apiBaseURL/boxes/$token"
            val name = remoteBoxConnectionData.name
            val box = addBoxToDb(Box(-1, name, token, baseUrl, BoxSendMethod.POLL, false))
            sender ! RemoteBoxAdded(box)

          case Connect(remoteBox) =>
            val box = pushBoxByBaseUrl(remoteBox.baseUrl) getOrElse {
              val token = baseUrlToToken(remoteBox.baseUrl)
              addBoxToDb(Box(-1, remoteBox.name, token, remoteBox.baseUrl, BoxSendMethod.PUSH, false))
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
            val boxes = getBoxesFromDb()
            sender ! Boxes(boxes)

          case GetBoxById(boxId) =>
            sender ! boxById(boxId)

          case GetBoxByToken(token) =>
            sender ! pollBoxByToken(token)

          case UpdateIncoming(box, outgoingTransactionId, totalImageCount, imageId) =>
            val existingTransaction = incomingTransactionByOutgoingTransactionId(box.id, outgoingTransactionId)
              .getOrElse(addIncomingTransaction(IncomingTransaction(-1, box.id, box.name, outgoingTransactionId, 0, totalImageCount, System.currentTimeMillis, TransactionStatus.WAITING)))
            val incomingTransaction = updateIncomingTransaction(existingTransaction.incrementReceived.updateTimestamp.copy(totalImageCount = totalImageCount))
            addIncomingImage(IncomingImage(-1, incomingTransaction.id, imageId))

            if (incomingTransaction.receivedImageCount == totalImageCount) {
              SbxLog.info("Box", s"Receiving $totalImageCount images from box ${box.name} completed.")
            }

            sender ! IncomingUpdated(incomingTransaction)

          case PollOutgoing(box) =>
            pollBoxesLastPollTimestamp(box.id) = System.currentTimeMillis

            val response = nextOutgoingTransactionImage(box.id) match {
              case Some(transactionImage) => transactionImage
              case None                   => OutgoingEmpty
            }

            sender ! response

          case SendToRemoteBox(box, imageTagValuesSeq) =>
            SbxLog.info("Box", s"Sending ${imageTagValuesSeq.length} images to box ${box.name}")
            addImagesToOutgoing(box.id, box.name, imageTagValuesSeq)
            sender ! ImagesAddedToOutgoing(box.id, imageTagValuesSeq.map(_.imageId))

          case GetOutgoingTransactionImage(box, outgoingTransactionId, outgoingImageId) =>
            sender ! outgoingTransactionImageById(box.id, outgoingTransactionId, outgoingImageId)

          case MarkOutgoingImageAsSent(box, transactionImage) =>
            updateOutgoingImage(transactionImage.image.copy(sent = true))
            val updatedTransaction = updateOutgoingTransaction(transactionImage.transaction.incrementSent.updateTimestamp)

            if (updatedTransaction.sentImageCount == updatedTransaction.totalImageCount) {
              context.system.eventStream.publish(ImagesSent(Destination(DestinationType.BOX, box.name, box.id), outgoingImageIdsForTransactionId(updatedTransaction.id)))
              markOutgoingTransactionAsFinished(transactionImage.transaction)
              SbxLog.info("Box", s"Finished sending ${updatedTransaction.totalImageCount} images to box ${box.name}")
            }

            sender ! OutgoingImageMarkedAsSent

          case MarkOutgoingTransactionAsFailed(box, failedTransactionImage) =>
            markOutgoingTransactionAsFailed(failedTransactionImage.transactionImage.transaction)
            SbxLog.error("Box", failedTransactionImage.message)
            sender ! OutgoingTransactionMarkedAsFailed

          case GetIncoming =>
            sender ! Incoming(getIncomingFromDb())

          case GetOutgoing =>
            sender ! Outgoing(getOutgoingFromDb())

          case GetImagesForIncomingTransaction(incomingTransactionId) =>
            val imageIds = getIncomingImagesByIncomingTransactionId(incomingTransactionId).map(_.imageId)
            getImageMetaData(imageIds).pipeTo(sender)

          case GetImagesForOutgoingTransaction(outgoingTransactionId) =>
            val imageIds = getOutgoingImagesByOutgoingTransactionId(outgoingTransactionId).map(_.imageId)
            getImageMetaData(imageIds).pipeTo(sender)

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
      context.actorOf(BoxPushActor.props(box, dbProps, timeout), actorName)
  }

  def maybeStartPollActor(box: Box): Unit = {
    val actorName = pollActorName(box)
    if (context.child(actorName).isEmpty)
      context.actorOf(BoxPollActor.props(box, dbProps, timeout), actorName)
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

  def getBoxesFromDb(): Seq[Box] =
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

  def updateIncomingTransaction(transaction: IncomingTransaction): IncomingTransaction = {
    db.withSession { implicit session =>
      boxDao.updateIncomingTransaction(transaction)
      transaction
    }
  }

  def updatePollBoxesOnlineStatus(): Unit = {
    val now = System.currentTimeMillis

    pollBoxesLastPollTimestamp.foreach {
      case (boxId, lastPollTime) =>
        val online =
          if (now - lastPollTime < pollBoxOnlineStatusTimeoutMillis)
            true
          else
            false

        updateBoxOnlineStatusInDb(boxId, online)
    }
  }

  def addImagesToOutgoing(boxId: Long, boxName: String, imageTagValuesSeq: Seq[ImageTagValues]) = {
    val outgoingTransaction = addOutgoingTransaction(boxId, boxName, imageTagValuesSeq.length)
    imageTagValuesSeq.foreach { imageTagValues =>
      val outgoingImage = addOutgoingImage(outgoingTransaction, imageTagValues.imageId)
      imageTagValues.tagValues.foreach { tagValue =>
        addOutgoingTagValue(outgoingImage, tagValue)
      }
    }
  }

  def addOutgoingTransaction(boxId: Long, boxName: String, totalImageCount: Int): OutgoingTransaction =
    db.withSession { implicit session =>
      boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, boxId, boxName, 0, totalImageCount, System.currentTimeMillis, TransactionStatus.WAITING))
    }

  def addOutgoingImage(outgoingTransaction: OutgoingTransaction, imageId: Long): OutgoingImage =
    db.withSession { implicit session =>
      boxDao.insertOutgoingImage(OutgoingImage(-1, outgoingTransaction.id, imageId, false))
    }

  def addOutgoingTagValue(outgoingImage: OutgoingImage, tagValue: TagValue): OutgoingTagValue =
    db.withSession { implicit session =>
      boxDao.insertOutgoingTagValue(OutgoingTagValue(-1, outgoingImage.id, tagValue))
    }

  def addIncomingTransaction(transaction: IncomingTransaction): IncomingTransaction =
    db.withSession { implicit session =>
      boxDao.insertIncomingTransaction(transaction)
    }

  def addIncomingImage(image: IncomingImage): IncomingImage =
    db.withSession { implicit session =>
      boxDao.insertIncomingImage(image)
    }

  def outgoingTransactionById(outgoingTransactionId: Long): Option[OutgoingTransaction] =
    db.withSession { implicit session =>
      boxDao.outgoingTransactionById(outgoingTransactionId)
    }

  def outgoingTransactionImageById(boxId: Long, outgoingTransactionId: Long, outgoingImageId: Long): Option[OutgoingTransactionImage] =
    db.withSession { implicit session =>
      boxDao.outgoingTransactionImageByOutgoingTransactionIdAndOutgoingImageId(boxId, outgoingTransactionId, outgoingImageId)
    }

  def removeIncomingTransactionFromDb(incomingTransactionId: Long) =
    db.withSession { implicit session =>
      boxDao.removeIncomingTransaction(incomingTransactionId)
    }

  def updateOutgoingTransaction(outgoingTransaction: OutgoingTransaction) =
    db.withSession { implicit session =>
      boxDao.updateOutgoingTransaction(outgoingTransaction)
      outgoingTransaction
    }

  def updateOutgoingImage(outgoingImage: OutgoingImage) =
    db.withSession { implicit session =>
      boxDao.updateOutgoingImage(outgoingImage)
      outgoingImage
    }

  def removeOutgoingTransactionFromDb(outgoingTransactionId: Long) =
    db.withSession { implicit session =>
      boxDao.removeOutgoingTransaction(outgoingTransactionId)
    }

  def markOutgoingTransactionAsFailed(transaction: OutgoingTransaction) =
    db.withSession { implicit session =>
      boxDao.setOutgoingTransactionStatus(transaction.id, TransactionStatus.FAILED)
    }

  def markOutgoingTransactionAsFinished(transaction: OutgoingTransaction) =
    db.withSession { implicit session =>
      boxDao.setOutgoingTransactionStatus(transaction.id, TransactionStatus.FINISHED)
    }

  def getIncomingFromDb() =
    db.withSession { implicit session =>
      boxDao.listIncomingEntries
    }

  def getOutgoingFromDb() =
    db.withSession { implicit session =>
      boxDao.listOutgoingEntries
    }

  def getIncomingImagesByIncomingTransactionId(incomingTransactionId: Long) =
    db.withSession { implicit session =>
      boxDao.listIncomingImagesForIncomingTransactionId(incomingTransactionId)
    }

  def getOutgoingImagesByOutgoingTransactionId(outgoingTransactionId: Long) =
    db.withSession { implicit session =>
      boxDao.listOutgoingImagesForOutgoingTransactionId(outgoingTransactionId)
    }

  def updateBoxOnlineStatusInDb(boxId: Long, online: Boolean): Unit =
    db.withSession { implicit session =>
      boxDao.updateBoxOnlineStatus(boxId, online)
    }

  def tagValuesForOutgoingTransactionImage(transactionImage: OutgoingTransactionImage): Seq[OutgoingTagValue] =
    db.withSession { implicit session =>
      boxDao.tagValuesByOutgoingTransactionIdAndImageId(transactionImage.transaction.id, transactionImage.image.id)
    }

  def getImageMetaData(imageIds: List[Long]): Future[Images] =
    Future.sequence(
      imageIds.map(imageId =>
        metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]))
      .map(imageMaybes => Images(imageMaybes.flatten))

  def incomingTransactionForImageId(imageId: Long): Option[IncomingTransaction] =
    db.withSession { implicit session =>
      boxDao.incomingTransactionByImageId(imageId)
    }

  def incomingTransactionByOutgoingTransactionId(boxId: Long, outgoingTransactionId: Long): Option[IncomingTransaction] =
    db.withSession { implicit session =>
      boxDao.incomingTransactionByOutgoingTransactionId(boxId, outgoingTransactionId)
    }

  def outgoingImageIdsForTransactionId(transactionId: Long): Seq[Long] =
    db.withSession { implicit session =>
      boxDao.outgoingImagesByOutgoingTransactionId(transactionId).map(_.imageId)
    }

  def removeImageFromBoxDb(imageId: Long) =
    db.withSession { implicit session =>
      // TODO fail transfers in progress
      boxDao.removeOutgoingImagesForImageId(imageId)
      boxDao.removeIncomingImagesForImageId(imageId)
    }

}

object BoxServiceActor {
  def props(dbProps: DbProps, apiBaseURL: String, timeout: Timeout): Props = Props(new BoxServiceActor(dbProps, apiBaseURL, timeout))
}
