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
            val baseUrl = s"$apiBaseURL/box/$token"
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

          case UpdateIncoming(box, transactionId, totalImageCount, imageId) =>
            val existingEntry = incomingEntryByTransactionId(box.id, transactionId)
              .getOrElse(addIncomingEntry(IncomingEntry(-1, box.id, box.name, transactionId, 0, totalImageCount, System.currentTimeMillis, TransactionStatus.WAITING)))
            val incomingEntry = updateIncomingEntry(existingEntry.incrementReceived.updateTimestamp.copy(totalImageCount = totalImageCount))
            addIncomingImage(IncomingImage(-1, incomingEntry.id, imageId))

            if (incomingEntry.receivedImageCount == totalImageCount) {
              SbxLog.info("Box", s"Receiving $totalImageCount images from box ${box.name} completed.")
            }

            sender ! IncomingUpdated(incomingEntry)

          case PollOutgoing(box) =>
            pollBoxesLastPollTimestamp(box.id) = System.currentTimeMillis

            val response = nextOutgoingEntryImage(box.id) match {
              case Some(entryImage) => entryImage
              case None                        => OutgoingEmpty
            }

            sender ! response

          case SendToRemoteBox(box, imageTagValuesSeq) =>
            SbxLog.info("Box", s"Sending ${imageTagValuesSeq.length} images to box ${box.name}")
            addImagesToOutgoing(box.id, box.name, imageTagValuesSeq)
            sender ! ImagesAddedToOutgoing(box.id, imageTagValuesSeq.map(_.imageId))

          case GetOutgoingEntryImage(box, transactionId, imageId) =>
            sender ! outgoingEntryImageByTransactionIdAndImageId(box.id, transactionId, imageId)

          case MarkOutgoingImageAsSent(box, entryImage) =>
            updateOutgoingImage(entryImage.image.copy(sent = true))
            val updatedEntry = updateOutgoingEntry(entryImage.entry.incrementSent.updateTimestamp)

            if (updatedEntry.sentImageCount == updatedEntry.totalImageCount) {
              context.system.eventStream.publish(ImagesSent(Destination(DestinationType.BOX, box.name, box.id), outgoingImageIdsForTransactionId(updatedEntry.transactionId)))
              markOutgoingTransactionAsFinished(box, updatedEntry.transactionId)
              removeTransactionTagValuesForTransactionId(updatedEntry.transactionId)
              SbxLog.info("Box", s"Finished sending ${updatedEntry.totalImageCount} images to box ${box.name}")
            }

            sender ! OutgoingImageMarkedAsSent

          case MarkOutgoingTransactionAsFailed(box, transactionId, message) =>
            markOutgoingTransactionAsFailed(box, transactionId, message)
            sender ! OutgoingTransactionMarkedAsFailed

          case GetIncoming =>
            sender ! Incoming(getIncomingFromDb())

          case GetOutgoing =>
            sender ! Outgoing(getOutgoingFromDb())

          case GetImagesForIncomingEntry(incomingEntryId) =>
            val imageIds = getIncomingImagesByIncomingEntryId(incomingEntryId).map(_.imageId)
            getImageMetaData(imageIds).pipeTo(sender)

          case GetImagesForOutgoingEntry(outgoingEntryId) =>
            val imageIds = getOutgoingImagesByOutgoingEntryId(outgoingEntryId).map(_.imageId)
            getImageMetaData(imageIds).pipeTo(sender)

          case RemoveOutgoingEntry(outgoingEntryId) =>
            removeOutgoingEntryFromDb(outgoingEntryId)
            sender ! OutgoingEntryRemoved(outgoingEntryId)
            
          case RemoveIncomingEntry(incomingEntryId) =>
            removeIncomingEntryFromDb(incomingEntryId)
            sender ! IncomingEntryRemoved(incomingEntryId)

          case GetTransactionTagValues(imageId, transactionId) =>
            sender ! tagValuesForImageIdAndTransactionId(imageId, transactionId)

          case GetIncomingEntryForImageId(imageId) =>
            sender ! incomingEntryForImageId(imageId)

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

  def nextOutgoingEntryImage(boxId: Long): Option[OutgoingEntryImage] =
    db.withSession { implicit session =>
      boxDao.nextOutgoingEntryImageForRemoteBoxId(boxId)
    }

  def updateIncomingEntry(entry: IncomingEntry): IncomingEntry = {
    db.withSession { implicit session =>
      boxDao.updateIncomingEntry(entry)
      entry
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

  def generateTransactionId(): Long =
    // Must be a positive number for the generated id to work in URLs which is very strange
    // Maybe switch to using Strings as transaction id?
    abs(UUID.randomUUID().getMostSignificantBits())

  def addImagesToOutgoing(remoteBoxId: Long, remoteBoxName: String, imageTagValuesSeq: Seq[ImageTagValues]) = {
    val transactionId = generateTransactionId()
    imageTagValuesSeq.foreach(imageTagValues =>
      imageTagValues.tagValues.foreach(tagValue =>
        addTagValue(transactionId, imageTagValues.imageId, tagValue)))
    addOutgoingEntries(remoteBoxId, remoteBoxName, transactionId, imageTagValuesSeq.map(_.imageId))
  }

  def addOutgoingEntries(remoteBoxId: Long, remoteBoxName: String, transactionId: Long, imageIds: Seq[Long]): Unit =
    db.withSession { implicit session =>
      val entry = boxDao.insertOutgoingEntry(OutgoingEntry(-1, remoteBoxId, remoteBoxName, transactionId, 0, imageIds.length, System.currentTimeMillis, TransactionStatus.WAITING))
      imageIds foreach { imageId =>
        boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, imageId, false))
      }
    }

  def addIncomingEntry(entry: IncomingEntry): IncomingEntry =
    db.withSession { implicit session =>
      boxDao.insertIncomingEntry(entry)
    }

  def addIncomingImage(image: IncomingImage): IncomingImage =
    db.withSession { implicit session =>
      boxDao.insertIncomingImage(image)
    }

  def addTagValue(transactionId: Long, imageId: Long, tagValue: TagValue) =
    db.withSession { implicit session =>
      boxDao.insertTransactionTagValue(
        TransactionTagValue(-1, transactionId, imageId, tagValue))
    }

  def outgoingEntryById(outgoingEntryId: Long): Option[OutgoingEntry] =
    db.withSession { implicit session =>
      boxDao.outgoingEntryById(outgoingEntryId)
    }

  def outgoingEntryImageByTransactionIdAndImageId(remoteBoxId: Long, transactionId: Long, imageId: Long): Option[OutgoingEntryImage] =
    db.withSession { implicit session =>
      boxDao.outgoingEntryImageByTransactionIdAndImageId(remoteBoxId, transactionId, imageId)
    }

  def removeIncomingEntryFromDb(incomingEntryId: Long) =
    db.withSession { implicit session =>
      boxDao.removeIncomingEntry(incomingEntryId)
    }

  def updateOutgoingEntry(outgoingEntry: OutgoingEntry) =
    db.withSession { implicit session =>
      boxDao.updateOutgoingEntry(outgoingEntry)
      outgoingEntry
    }

  def updateOutgoingImage(outgoingImage: OutgoingImage) =
    db.withSession { implicit session =>
      boxDao.updateOutgoingImage(outgoingImage)
      outgoingImage
    }

  def removeOutgoingEntryFromDb(outgoingEntryId: Long) =
    db.withSession { implicit session =>
      boxDao.removeOutgoingEntry(outgoingEntryId)
    }

  def markOutgoingTransactionAsFailed(box: Box, transactionId: Long, message: String) = {
    db.withSession { implicit session =>
      boxDao.setOutgoingTransactionStatus(box.id, transactionId, TransactionStatus.FAILED)
    }
    SbxLog.error("Box", message)
  }

  def markOutgoingTransactionAsFinished(box: Box, transactionId: Long) =
    db.withSession { implicit session =>
      boxDao.setOutgoingTransactionStatus(box.id, transactionId, TransactionStatus.FINISHED)
    }

  def getIncomingFromDb() =
    db.withSession { implicit session =>
      boxDao.listIncomingEntries
    }

  def getOutgoingFromDb() =
    db.withSession { implicit session =>
      boxDao.listOutgoingEntries
    }

  def getIncomingImagesByIncomingEntryId(incomingEntryId: Long) =
    db.withSession { implicit session =>
      boxDao.listIncomingImagesForIncomingEntryId(incomingEntryId)
    }

  def getOutgoingImagesByOutgoingEntryId(outgoingEntryId: Long) =
    db.withSession { implicit session =>
      boxDao.listOutgoingImagesForOutgoingEntryId(outgoingEntryId)
    }

  def updateBoxOnlineStatusInDb(boxId: Long, online: Boolean): Unit =
    db.withSession { implicit session =>
      boxDao.updateBoxOnlineStatus(boxId, online)
    }

  def tagValuesForImageIdAndTransactionId(imageId: Long, transactionId: Long): Seq[TransactionTagValue] =
    db.withSession { implicit session =>
      boxDao.tagValuesByImageIdAndTransactionId(imageId, transactionId)
    }

  def removeTransactionTagValuesForTransactionId(transactionId: Long) =
    db.withSession { implicit session =>
      boxDao.removeTransactionTagValuesByTransactionId(transactionId)
    }

  def getImageMetaData(imageIds: List[Long]): Future[Images] =
    Future.sequence(
      imageIds.map(imageId =>
        metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]))
      .map(imageMaybes => Images(imageMaybes.flatten))

  def incomingEntryForImageId(imageId: Long): Option[IncomingEntry] =
    db.withSession { implicit session =>
      boxDao.incomingEntryByImageId(imageId)
    }

  def incomingEntryByTransactionId(remoteBoxId: Long, transactionId: Long): Option[IncomingEntry] =
    db.withSession { implicit session =>
      boxDao.incomingEntryByTransactionId(remoteBoxId, transactionId)
    }

  def outgoingImageIdsForTransactionId(transactionId: Long): Seq[Long] =
    db.withSession { implicit session =>
      boxDao.outgoingImagesByTransactionId(transactionId).map(_.imageId)
    }

  def removeImageFromBoxDb(imageId: Long) =
    db.withSession { implicit session =>
      // TODO fail transfers in progress
      boxDao.removeOutgoingImagesForImageId(imageId)
      boxDao.removeIncomingImagesForImageId(imageId)
      boxDao.removeTransactionTagValuesForImageId(imageId)
    }

}

object BoxServiceActor {
  def props(dbProps: DbProps, apiBaseURL: String, timeout: Timeout): Props = Props(new BoxServiceActor(dbProps, apiBaseURL, timeout))
}
