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

import se.nimsa.sbx.app.DbProps
import akka.actor.Actor
import akka.event.Logging
import akka.event.LoggingReceive
import akka.pattern.ask
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import akka.pattern.pipe
import akka.actor.Props
import akka.actor.PoisonPill
import java.util.UUID
import akka.actor.Status.Failure
import se.nimsa.sbx.util.ExceptionCatching
import java.nio.file.Path
import scala.math.abs
import java.util.Date
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import akka.actor.ActorSelection
import akka.util.Timeout
import scala.concurrent.Future
import scala.concurrent.Future.sequence
import akka.actor.Stash
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.anonymization.AnonymizationProtocol.TagValue
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.util.CryptoUtil

class BoxServiceActor(dbProps: DbProps, apiBaseURL: String, implicit val timeout: Timeout) extends Actor with Stash with ExceptionCatching {

  case object UpdatePollBoxesOnlineStatus

  val log = Logging(context.system, this)

  val db = dbProps.db
  val boxDao = new BoxDAO(dbProps.driver)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val pollBoxOnlineStatusTimeoutMillis: Long = 15000
  val pollBoxesLastPollTimestamp = collection.mutable.Map.empty[Long, Date]

  val storageService = context.actorSelection("../StorageService")

  setupBoxes()

  val pollBoxesOnlineStatusSchedule = system.scheduler.schedule(100.milliseconds, 5.seconds) {
    self ! UpdatePollBoxesOnlineStatus
  }

  log.info("Box service started")

  override def postStop() =
    pollBoxesOnlineStatusSchedule.cancel()

  def receive = LoggingReceive {

    case UpdatePollBoxesOnlineStatus =>
      updatePollBoxesOnlineStatus()

    case msg: BoxRequest =>

      catchAndReport {

        msg match {

          case CreateConnection(remoteBoxConnectionData) =>
            val token = UUID.randomUUID().toString()
            val baseUrl = s"$apiBaseURL/box/$token"
            val name = remoteBoxConnectionData.name
            val box = addBoxToDb(Box(-1, name, token, baseUrl, BoxSendMethod.POLL, false))
            val secret = CryptoUtil.createBase64EncodedKey
            val transferData = addBoxTransferDataToDb(BoxTransferData(box.id, secret))
            sender ! RemoteBoxAdded(box, transferData)

          case Connect(remoteBox) =>
            val box = pushBoxByBaseUrl(remoteBox.baseUrl) getOrElse {
              val token = baseUrlToToken(remoteBox.baseUrl)
              addBoxToDb(Box(-1, remoteBox.name, token, remoteBox.baseUrl, BoxSendMethod.PUSH, false))
            }
            val transferData = boxTransferDataByBoxId(box.id) getOrElse {
              val secret = remoteBox.secret
              addBoxTransferDataToDb(BoxTransferData(box.id, secret))
            }
            maybeStartPushActor(box, transferData)
            maybeStartPollActor(box, transferData)
            sender ! RemoteBoxAdded(box, transferData)

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

          case GetBoxTransferDataByBoxId(boxId) =>
            sender ! boxTransferDataByBoxId(boxId)

          case GetBoxByToken(token) =>
            sender ! pollBoxByToken(token)

          case UpdateInbox(token, transactionId, sequenceNumber, totalImageCount, imageId) =>
            pollBoxByToken(token).foreach(box =>
              updateInbox(box.id, box.name, transactionId, sequenceNumber, totalImageCount, imageId))

            // TODO: what should we do if no box was found for token?

            sender ! InboxUpdated(token, transactionId, sequenceNumber, totalImageCount)

          case PollOutbox(token) =>
            pollBoxByToken(token).foreach(box => {
              pollBoxesLastPollTimestamp(box.id) = new Date()

              nextOutboxEntry(box.id) match {
                case Some(outboxEntry) => sender ! outboxEntry
                case None              => sender ! OutboxEmpty
              }
            })

          // TODO: what should we do if no box was found for token?

          case SendToRemoteBox(remoteBoxId, imageTagValuesSeq) =>
            boxById(remoteBoxId) match {
              case Some(box) =>
                SbxLog.info("Box", s"Sending ${imageTagValuesSeq.length} images to box ${box.name}")
                addImagesToOutbox(remoteBoxId, box.name, imageTagValuesSeq)
                sender ! ImagesAddedToOutbox(remoteBoxId, imageTagValuesSeq.map(_.imageId))
              case None =>
                sender ! BoxNotFound
            }

          case GetOutboxEntry(token, transactionId, sequenceNumber) =>
            pollBoxByToken(token).foreach(box => {
              outboxEntryByTransactionIdAndSequenceNumber(box.id, transactionId, sequenceNumber) match {
                case Some(outboxEntry) => sender ! outboxEntry
                case None              => sender ! OutboxEntryNotFound
              }
            })

          case DeleteOutboxEntry(token, transactionId, sequenceNumber) =>
            pollBoxByToken(token).foreach(box => {
              outboxEntryByTransactionIdAndSequenceNumber(box.id, transactionId, sequenceNumber) match {
                case Some(outboxEntry) =>
                  removeOutboxEntryFromDb(outboxEntry.id)
                  updateSent(outboxEntry)

                  if (outboxEntry.sequenceNumber == outboxEntry.totalImageCount) {
                    context.system.eventStream.publish(ImagesSent(Destination(DestinationType.BOX, box.name, box.id), sentImageIdsForTransactionId(outboxEntry.transactionId)))
                    removeTransactionTagValuesForTransactionId(outboxEntry.transactionId)
                    SbxLog.info("Box", s"Finished sending ${outboxEntry.totalImageCount} images to box ${box.name}")
                  }

                  sender ! OutboxEntryDeleted
                case None =>
                  sender ! OutboxEntryDeleted
              }
            })

          case MarkOutboxTransactionAsFailed(token, transactionId, message) =>
            pollBoxByToken(token).foreach(box => {
              markOutboxTransactionAsFailed(box, transactionId, message)
              sender ! OutboxTransactionMarkedAsFailed
            })

          case GetInbox =>
            sender ! Inbox(getInboxFromDb())

          case GetOutbox =>
            sender ! Outbox(getOutboxFromDb())

          case GetSent =>
            sender ! Sent(getSentFromDb())

          case GetImagesForInboxEntry(inboxEntryId) =>
            val imageIds = getInboxImagesByInboxEntryId(inboxEntryId).map(_.imageId)
            getImagesFromStorage(imageIds).pipeTo(sender)

          case GetImagesForSentEntry(sentEntryId) =>
            val imageIds = getSentImagesBySentEntryId(sentEntryId).map(_.imageId)
            getImagesFromStorage(imageIds).pipeTo(sender)

          case RemoveOutboxEntry(outboxEntryId) =>
            outboxEntryById(outboxEntryId)
              .filter(outboxEntry => outboxEntry.sequenceNumber == outboxEntry.totalImageCount)
              .foreach(outboxEntry =>
                removeTransactionTagValuesForTransactionId(outboxEntry.transactionId))
            removeOutboxEntryFromDb(outboxEntryId)
            sender ! OutboxEntryRemoved(outboxEntryId)

          case RemoveInboxEntry(inboxEntryId) =>
            removeInboxEntryFromDb(inboxEntryId)
            sender ! InboxEntryRemoved(inboxEntryId)

          case RemoveSentEntry(sentEntryId) =>
            removeSentEntryFromDb(sentEntryId)
            sender ! SentEntryRemoved(sentEntryId)

          case GetTransactionTagValues(imageId, transactionId) =>
            sender ! tagValuesForImageIdAndTransactionId(imageId, transactionId)

          case GetInboxEntryForImageId(imageId) =>
            sender ! inboxEntryForImageId(imageId)

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
    getBoxesFromDb foreach (box => {
      boxTransferDataByBoxId(box.id) foreach (transferData =>
      box.sendMethod match {
        case BoxSendMethod.PUSH => {
          maybeStartPushActor(box, transferData)
          maybeStartPollActor(box, transferData)
        }
        case BoxSendMethod.POLL =>
          pollBoxesLastPollTimestamp(box.id) = new Date(0)
      })
    })

  def maybeStartPushActor(box: Box, boxTransferData: BoxTransferData): Unit = {
    val actorName = pushActorName(box)
    if (context.child(actorName).isEmpty)
      context.actorOf(BoxPushActor.props(box, boxTransferData, dbProps, timeout), actorName)
  }

  def maybeStartPollActor(box: Box, boxTransferData: BoxTransferData): Unit = {
    val actorName = pollActorName(box)
    if (context.child(actorName).isEmpty)
      context.actorOf(BoxPollActor.props(box, boxTransferData, dbProps, timeout), actorName)
  }

  def pushActorName(box: Box): String = BoxSendMethod.PUSH + "-" + box.id.toString

  def pollActorName(box: Box): String = BoxSendMethod.POLL + "-" + box.id.toString

  def addBoxToDb(box: Box): Box =
    db.withSession { implicit session =>
      if (boxDao.boxByName(box.name).isDefined)
        throw new IllegalArgumentException(s"A box with name ${box.name} already exists")
      boxDao.insertBox(box)
    }

  def addBoxTransferDataToDb(boxTransferData: BoxTransferData): BoxTransferData =
    db.withSession { implicit session =>
      if (boxDao.boxTransferDataByBoxId(boxTransferData.id).isDefined)
        throw new IllegalArgumentException(s"Box tranfer data for box with id ${boxTransferData.id} already exists")
      boxDao.insertBoxTransferData(boxTransferData)
      boxTransferData
    }

  def boxById(boxId: Long): Option[Box] =
    db.withSession { implicit session =>
      boxDao.boxById(boxId)
    }

  def boxTransferDataByBoxId(boxId: Long): Option[BoxTransferData] =
    db.withSession { implicit session =>
      boxDao.boxTransferDataByBoxId(boxId)
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

  def nextOutboxEntry(boxId: Long): Option[OutboxEntry] =
    db.withSession { implicit session =>
      boxDao.nextOutboxEntryForRemoteBoxId(boxId)
    }

  def updateInbox(remoteBoxId: Long, remoteBoxName: String, transactionId: Long, sequenceNumber: Long, totalImageCount: Long, imageId: Long): Unit = {
    db.withSession { implicit session =>
      val inboxEntry = boxDao.updateInbox(remoteBoxId, remoteBoxName, transactionId, sequenceNumber, totalImageCount)
      boxDao.insertInboxImage(InboxImage(-1, inboxEntry.id, imageId))
    }

    if (sequenceNumber == totalImageCount) {
      val boxName = boxById(remoteBoxId).map(_.name).getOrElse(remoteBoxId.toString)
      SbxLog.info("Box", s"Receiving ${totalImageCount} images from box $boxName completed.")
    }
  }

  def updatePollBoxesOnlineStatus(): Unit = {
    val now = new Date()

    pollBoxesLastPollTimestamp.foreach {
      case (boxId, lastPollTime) =>
        val online =
          if (now.getTime - lastPollTime.getTime < pollBoxOnlineStatusTimeoutMillis)
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

  def addImagesToOutbox(remoteBoxId: Long, remoteBoxName: String, imageTagValuesSeq: Seq[ImageTagValues]) = {
    val transactionId = generateTransactionId()
    imageTagValuesSeq.foreach(imageTagValues =>
      imageTagValues.tagValues.foreach(tagValue =>
        addTagValue(transactionId, imageTagValues.imageId, tagValue)))
    addOutboxEntries(remoteBoxId, remoteBoxName, transactionId, imageTagValuesSeq.map(_.imageId))
  }

  def addOutboxEntries(remoteBoxId: Long, remoteBoxName: String, transactionId: Long, imageIds: Seq[Long]): Unit = {
    val totalImageCount = imageIds.length

    db.withSession { implicit session =>
      for (sequenceNumber <- 1 to totalImageCount) {
        boxDao.insertOutboxEntry(OutboxEntry(-1, remoteBoxId, remoteBoxName, transactionId, sequenceNumber, totalImageCount, imageIds(sequenceNumber - 1), false))
      }
    }
  }

  def addTagValue(transactionId: Long, imageId: Long, tagValue: TagValue) =
    db.withSession { implicit session =>
      boxDao.insertTransactionTagValue(
        TransactionTagValue(-1, transactionId, imageId, tagValue))
    }

  def outboxEntryById(outboxEntryId: Long): Option[OutboxEntry] =
    db.withSession { implicit session =>
      boxDao.outboxEntryById(outboxEntryId)
    }

  def outboxEntryByTransactionIdAndSequenceNumber(remoteBoxId: Long, transactionId: Long, sequenceNumber: Long): Option[OutboxEntry] =
    db.withSession { implicit session =>
      boxDao.outboxEntryByTransactionIdAndSequenceNumber(remoteBoxId, transactionId, sequenceNumber)
    }

  def removeInboxEntryFromDb(inboxEntryId: Long) =
    db.withSession { implicit session =>
      boxDao.removeInboxEntry(inboxEntryId)
    }

  def removeOutboxEntryFromDb(outboxEntryId: Long) =
    db.withSession { implicit session =>
      boxDao.removeOutboxEntry(outboxEntryId)
    }

  def removeSentEntryFromDb(sentEntryId: Long) =
    db.withSession { implicit session =>
      boxDao.removeSentEntry(sentEntryId)
    }

  def updateSent(outboxEntry: OutboxEntry) =
    db.withSession { implicit session =>
      val sentEntry = boxDao.updateSent(outboxEntry.remoteBoxId, outboxEntry.remoteBoxName, outboxEntry.transactionId, outboxEntry.sequenceNumber, outboxEntry.totalImageCount)
      boxDao.insertSentImage(SentImage(-1, sentEntry.id, outboxEntry.imageId))
    }

  def markOutboxTransactionAsFailed(box: Box, transactionId: Long, message: String) = {
    db.withSession { implicit session =>
      boxDao.markOutboxTransactionAsFailed(box.id, transactionId)
    }
    SbxLog.error("Box", message)
  }

  def getInboxFromDb() =
    db.withSession { implicit session =>
      boxDao.listInboxEntries
    }

  def getOutboxFromDb() =
    db.withSession { implicit session =>
      boxDao.listOutboxEntries
    }

  def getSentFromDb() =
    db.withSession { implicit session =>
      boxDao.listSentEntries
    }

  def getInboxImagesByInboxEntryId(inboxEntryId: Long) =
    db.withSession { implicit session =>
      boxDao.listInboxImagesForInboxEntryId(inboxEntryId)
    }

  def getSentImagesBySentEntryId(sentEntryId: Long) =
    db.withSession { implicit session =>
      boxDao.listSentImagesForSentEntryId(sentEntryId)
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

  def getImagesFromStorage(imageIds: List[Long]): Future[Images] =
    Future.sequence(
      imageIds.map(imageId =>
        storageService.ask(GetImage(imageId)).mapTo[Option[Image]]))
      .map(imageMaybes => Images(imageMaybes.flatten))

  def inboxEntryForImageId(imageId: Long): Option[InboxEntry] =
    db.withSession { implicit session =>
      boxDao.inboxEntryByImageId(imageId)
    }

  def sentImageIdsForTransactionId(transactionId: Long): Seq[Long] =
    db.withSession { implicit session =>
      boxDao.sentImagesByTransactionId(transactionId).map(_.imageId)
    }

}

object BoxServiceActor {
  def props(dbProps: DbProps, apiBaseURL: String, timeout: Timeout): Props = Props(new BoxServiceActor(dbProps, apiBaseURL, timeout))
}
