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
import akka.pattern.pipe
import akka.actor.Props
import akka.actor.PoisonPill
import java.util.UUID
import akka.actor.Status.Failure
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.util.SequentialPipeToSupport
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
import BoxUtil._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.TagValue

class BoxServiceActor(dbProps: DbProps, storage: Path, apiBaseURL: String) extends Actor with Stash
  with SequentialPipeToSupport with ExceptionCatching {

  case object UpdatePollBoxesOnlineStatus

  val log = Logging(context.system, this)

  val db = dbProps.db
  val boxDao = new BoxDAO(dbProps.driver)

  val storageService = context.actorSelection("../StorageService")

  implicit val system = context.system
  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(70.seconds)

  val pollBoxOnlineStatusTimeoutMillis: Long = 15000
  val pollBoxesLastPollTimestamp = collection.mutable.Map.empty[Long, Date]

  setupDb()
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

          case CreateConnection(remoteBoxName) =>
            val token = UUID.randomUUID().toString()
            val baseUrl = s"$apiBaseURL/box/$token"
            val box = addBoxToDb(Box(-1, remoteBoxName, token, baseUrl, BoxSendMethod.POLL, false))
            sender ! RemoteBoxAdded(box)

          case Connect(remoteBox) =>
            val box = pushBoxByBaseUrl(remoteBox.baseUrl) getOrElse {
              val token = baseUrlToToken(remoteBox.baseUrl)
              val box = Box(-1, remoteBox.name, token, remoteBox.baseUrl, BoxSendMethod.PUSH, false)
              addBoxToDb(box)
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

          case UpdateInbox(token, transactionId, sequenceNumber, totalImageCount) =>
            pollBoxByToken(token).foreach(box =>
              updateInbox(box.id, transactionId, sequenceNumber, totalImageCount))

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

          case SendSeriesToRemoteBox(remoteBoxId, seriesIds, tagValues) =>
            boxById(remoteBoxId) match {
              case Some(box) =>
                SbxLog.info("Box", s"Sending series ${seriesIds.mkString(",")} to box ${box.name} with tag values ${tagValues.map(_.value).mkString(",")}")
                val imageFileIds = sendEntities(remoteBoxId, seriesIds, tagValues, imageFileIdsForSeries _)
                imageFileIds.map(ids => ImagesSent(remoteBoxId, ids)).pipeSequentiallyTo(sender)
              case None =>
                sender ! BoxNotFound
            }

          case SendStudiesToRemoteBox(remoteBoxId, studyIds, tagValues) =>
            boxById(remoteBoxId) match {
              case Some(box) =>
                SbxLog.info("Box", s"Sending study(s) ${studyIds.mkString(",")} to box ${box.name} with tag values ${tagValues.map(_.value).mkString(",")}")
                val imageFileIds = sendEntities(remoteBoxId, studyIds, tagValues, imageFileIdsForStudy _)
                imageFileIds.map(ids => ImagesSent(remoteBoxId, ids)).pipeSequentiallyTo(sender)
              case None =>
                sender ! BoxNotFound
            }

          case SendPatientsToRemoteBox(remoteBoxId, patientIds, tagValues) =>
            boxById(remoteBoxId) match {
              case Some(box) =>
                SbxLog.info("Box", s"Sending patient(s) ${patientIds.mkString(",")} to box ${box.name} with tag values ${tagValues.map(_.value).mkString(",")}")
                val imageFileIds = sendEntities(remoteBoxId, patientIds, tagValues, imageFileIdsForPatient _)
                imageFileIds.map(ids => ImagesSent(remoteBoxId, ids)).pipeSequentiallyTo(sender)
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

                  if (outboxEntry.sequenceNumber == outboxEntry.totalImageCount) {
                    removeTransactionTagValuesForTransactionId(outboxEntry.transactionId)
                    SbxLog.info("Box", s"Finished sending ${outboxEntry.totalImageCount} images to box ${box.name}")
                  }

                  sender ! OutboxEntryDeleted
                case None =>
                  sender ! OutboxEntryDeleted
              }
            })

          case GetInbox =>
            val inboxEntries = getInboxFromDb().map { inboxEntry =>
              boxById(inboxEntry.remoteBoxId) match {
                case Some(box) => InboxEntryInfo(box.name, inboxEntry.transactionId, inboxEntry.receivedImageCount, inboxEntry.totalImageCount)
                case None      => InboxEntryInfo(inboxEntry.remoteBoxId.toString, inboxEntry.transactionId, inboxEntry.receivedImageCount, inboxEntry.totalImageCount)
              }
            }
            sender ! Inbox(inboxEntries)

          case GetOutbox =>
            val idToBox = getBoxesFromDb().map(box => box.id -> box).toMap
            val outboxEntries = getOutboxFromDb().map { outboxEntry =>
              idToBox.get(outboxEntry.remoteBoxId) match {
                case Some(box) =>
                  OutboxEntryInfo(outboxEntry.id, box.name, outboxEntry.transactionId, outboxEntry.sequenceNumber, outboxEntry.totalImageCount, outboxEntry.imageFileId, outboxEntry.failed)
                case None =>
                  OutboxEntryInfo(outboxEntry.id, "" + outboxEntry.remoteBoxId, outboxEntry.transactionId, outboxEntry.sequenceNumber, outboxEntry.totalImageCount, outboxEntry.imageFileId, outboxEntry.failed)
              }
            }
            sender ! Outbox(outboxEntries)

          case RemoveOutboxEntry(outboxEntryId) =>
            outboxEntryById(outboxEntryId)
              .filter(outboxEntry => outboxEntry.sequenceNumber == outboxEntry.totalImageCount)
              .foreach(outboxEntry =>
                removeTransactionTagValuesForTransactionId(outboxEntry.transactionId))
            removeOutboxEntryFromDb(outboxEntryId)
            sender ! OutboxEntryRemoved(outboxEntryId)

          case GetTransactionTagValues(imageFileId, transactionId) =>
            sender ! tagValuesForImageFileIdAndTransactionId(imageFileId, transactionId)
        }

      }

  }

  def setupDb(): Unit =
    db.withSession { implicit session =>
      boxDao.create
    }

  def teardownDb(): Unit =
    db.withSession { implicit session =>
      boxDao.drop
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
    getBoxesFromDb foreach (box => box.sendMethod match {
      case BoxSendMethod.PUSH => {
        maybeStartPushActor(box)
        maybeStartPollActor(box)
      }
      case BoxSendMethod.POLL =>
        pollBoxesLastPollTimestamp(box.id) = new Date(0)
    })

  def maybeStartPushActor(box: Box): Unit = {
    val actorName = pushActorName(box)
    if (context.child(actorName).isEmpty)
      context.actorOf(BoxPushActor.props(box, dbProps, storage), actorName)
  }

  def maybeStartPollActor(box: Box): Unit = {
    val actorName = pollActorName(box)
    if (context.child(actorName).isEmpty)
      context.actorOf(BoxPollActor.props(box, dbProps), actorName)
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

  def nextOutboxEntry(boxId: Long): Option[OutboxEntry] =
    db.withSession { implicit session =>
      boxDao.nextOutboxEntryForRemoteBoxId(boxId)
    }

  def updateInbox(remoteBoxId: Long, transactionId: Long, sequenceNumber: Long, totalImageCount: Long): Unit = {
    db.withSession { implicit session =>
      boxDao.updateInbox(remoteBoxId, transactionId, sequenceNumber, totalImageCount)
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

  def sendEntities(remoteBoxId: Long, entityIds: Seq[Long], tagValues: Seq[BoxSendTagValue], imageFileIdsForEntityId: Long => Future[Seq[Long]]) = {
    val transactionId = generateTransactionId()
    val futureImageFileIdToTagValues = sequence(entityIds.map(entityId => imageFileIdsForEntityId(entityId)
      .map(_.map(_ -> tagValues.filter(_.entityId == entityId))))).map(_.flatten.toMap)
    futureImageFileIdToTagValues.map(imageFileIdToTagValues => {
      val imageFileIds = imageFileIdToTagValues.keys.toSeq
      addOutboxEntries(remoteBoxId, transactionId, imageFileIds)
      for ((imageFileId, tagValues) <- imageFileIdToTagValues) {
        tagValues.foreach(tagValue =>
          addTagValue(imageFileId, transactionId, tagValue.tag, tagValue.value))
      }
      imageFileIds
    })
  }

  def imageFileIdsForSeries(seriesId: Long) =
    getImages(seriesId)
      .flatMap(images => sequence(images.map(image => getImageFile(image.id).map(_.map(imageFile => imageFile.id))))).map(_.flatten)

  def imageFileIdsForStudy(studyId: Long) =
    getSeries(studyId)
      .flatMap(seriesCollection => sequence(seriesCollection.map(series => imageFileIdsForSeries(series.id)))).map(_.flatten)

  def imageFileIdsForPatient(patientId: Long) =
    getStudies(patientId)
      .flatMap(studies => sequence(studies.map(study => imageFileIdsForStudy(study.id)))).map(_.flatten)

  def getStudies(patientId: Long) = storageService.ask(GetStudies(0, Integer.MAX_VALUE, patientId, None, None)).mapTo[Studies].map(_.studies)
  def getSeries(studyId: Long) = storageService.ask(GetSeries(0, Integer.MAX_VALUE, studyId, None, None)).mapTo[SeriesCollection].map(_.series)
  def getImages(seriesId: Long) = storageService.ask(GetImages(0, 100000, seriesId)).mapTo[Images].map(_.images)
  def getImageFile(imageId: Long) = storageService.ask(GetImageFile(imageId)).mapTo[Option[ImageFile]]

  def addOutboxEntries(remoteBoxId: Long, transactionId: Long, imageFileIds: Seq[Long]): Unit = {
    val totalImageCount = imageFileIds.length

    db.withSession { implicit session =>
      for (sequenceNumber <- 1 to totalImageCount) {
        boxDao.insertOutboxEntry(OutboxEntry(-1, remoteBoxId, transactionId, sequenceNumber, totalImageCount, imageFileIds(sequenceNumber - 1), false))
      }
    }
  }

  def addTagValue(transactionId: Long, imageId: Long, tag: Int, value: String) =
    db.withSession { implicit session =>
      boxDao.insertTransactionTagValue(
        TransactionTagValue(-1, transactionId, TagValue(imageId, tag, value)))
    }

  def outboxEntryById(outboxEntryId: Long): Option[OutboxEntry] =
    db.withSession { implicit session =>
      boxDao.outboxEntryById(outboxEntryId)
    }

  def outboxEntryByTransactionIdAndSequenceNumber(remoteBoxId: Long, transactionId: Long, sequenceNumber: Long): Option[OutboxEntry] =
    db.withSession { implicit session =>
      boxDao.outboxEntryByTransactionIdAndSequenceNumber(remoteBoxId, transactionId, sequenceNumber)
    }

  def removeOutboxEntryFromDb(outboxEntryId: Long) =
    db.withSession { implicit session =>
      boxDao.removeOutboxEntry(outboxEntryId)
    }

  def getInboxFromDb() =
    db.withSession { implicit session =>
      boxDao.listInboxEntries
    }

  def getOutboxFromDb() =
    db.withSession { implicit session =>
      boxDao.listOutboxEntries
    }

  def updateBoxOnlineStatusInDb(boxId: Long, online: Boolean): Unit =
    db.withSession { implicit session =>
      boxDao.updateBoxOnlineStatus(boxId, online)
    }

  def tagValuesForImageFileIdAndTransactionId(imageFileId: Long, transactionId: Long): Seq[TransactionTagValue] =
    db.withSession { implicit session =>
      boxDao.tagValuesByImageFileIdAndTransactionId(imageFileId, transactionId)
    }

  def removeTransactionTagValuesForTransactionId(transactionId: Long) =
    db.withSession { implicit session =>
      boxDao.removeTransactionTagValuesByTransactionId(transactionId)
    }

}

object BoxServiceActor {
  def props(dbProps: DbProps, storage: Path, apiBaseURL: String): Props = Props(new BoxServiceActor(dbProps, storage, apiBaseURL))
}
