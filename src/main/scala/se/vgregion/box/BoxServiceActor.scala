package se.vgregion.box

import se.vgregion.app.DbProps
import akka.actor.Actor
import akka.event.LoggingReceive
import se.vgregion.box.BoxProtocol._
import se.vgregion.log.LogProtocol._
import se.vgregion.dicom.DicomMetaDataDAO
import akka.actor.Props
import akka.actor.PoisonPill
import java.util.UUID
import akka.actor.Status.Failure
import se.vgregion.util.ExceptionCatching
import java.nio.file.Path
import scala.math.abs
import java.util.Date
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

class BoxServiceActor(dbProps: DbProps, storage: Path, apiBaseURL: String) extends Actor with ExceptionCatching {

  case object UpdatePollBoxesOnlineStatus

  val db = dbProps.db
  val boxDao = new BoxDAO(dbProps.driver)
  val metaDataDao = new DicomMetaDataDAO(dbProps.driver)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val pollBoxOnlineStatusTimeoutMillis: Long = 15000
  val pollBoxesLastPollTimestamp = collection.mutable.Map.empty[Long, Date]

  setupDb()
  setupBoxes()

  val pollBoxesOnlineStatusSchedule = system.scheduler.schedule(100.milliseconds, 5.seconds) {
    self ! UpdatePollBoxesOnlineStatus
  }

  override def postStop() =
    pollBoxesOnlineStatusSchedule.cancel()

  def receive = LoggingReceive {

    case UpdatePollBoxesOnlineStatus =>
      updatePollBoxesOnlineStatus()

    case msg: BoxRequest =>

      catchAndReport {

        msg match {

          case GenerateBoxBaseUrl(remoteBoxName) =>
            val token = UUID.randomUUID().toString()
            val baseUrl = s"$apiBaseURL/box/$token"
            val box = Box(-1, remoteBoxName, token, baseUrl, BoxSendMethod.POLL, false)
            addBoxToDb(box)
            sender ! BoxBaseUrlGenerated(baseUrl)

          case AddRemoteBox(remoteBox) =>
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

          case ValidateToken(token) =>
            if (tokenIsValid(token))
              sender ! ValidToken(token)
            else
              sender ! InvalidToken(token)

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
                val imageFileIds = sendEntities(remoteBoxId, seriesIds, tagValues, imageFileIdsForSeries _)
                sender ! ImagesSent(remoteBoxId, imageFileIds)
              case None =>
                sender ! BoxNotFound
            }

          case SendStudiesToRemoteBox(remoteBoxId, studyIds, tagValues) =>
            boxById(remoteBoxId) match {
              case Some(box) =>
                val imageFileIds = sendEntities(remoteBoxId, studyIds, tagValues, imageFileIdsForStudy _)
                sender ! ImagesSent(remoteBoxId, imageFileIds)
              case None =>
                sender ! BoxNotFound
            }

          case SendPatientsToRemoteBox(remoteBoxId, patientIds, tagValues) =>
            boxById(remoteBoxId) match {
              case Some(box) =>
                val imageFileIds = sendEntities(remoteBoxId, patientIds, tagValues, imageFileIdsForPatient _)
                sender ! ImagesSent(remoteBoxId, imageFileIds)
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
                    context.system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.INFO, "Box", "Send completed.")))
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
      boxDao.insertBox(box)
    }

  def boxById(boxId: Long): Option[Box] =
    db.withSession { implicit session =>
      boxDao.boxById(boxId)
    }

  def pollBoxByToken(token: String): Option[Box] =
    db.withSession { implicit session =>
      boxDao.pollBoxByToken(token)
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

  def tokenIsValid(token: String): Boolean =
    db.withSession { implicit session =>
      boxDao.pollBoxByToken(token).isDefined
    }

  def nextOutboxEntry(boxId: Long): Option[OutboxEntry] =
    db.withSession { implicit session =>
      boxDao.nextOutboxEntryForRemoteBoxId(boxId)
    }

  def updateInbox(remoteBoxId: Long, transactionId: Long, sequenceNumber: Long, totalImageCount: Long): Unit = {
    db.withSession { implicit session =>
      boxDao.updateInbox(remoteBoxId, transactionId, sequenceNumber, totalImageCount)
    }

    if (sequenceNumber == totalImageCount)
      context.system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.INFO, "Box", "Receive completed.")))
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

  def sendEntities(remoteBoxId: Long, entityIds: Seq[Long], tagValues: Seq[BoxSendTagValue], imageFileIdsForEntityId: Long => Seq[Long]) = {
    val transactionId = generateTransactionId()
    val imageFileIdToTagValues = entityIds.flatMap(entityId => imageFileIdsForEntityId(entityId).map(_ -> tagValues.filter(_.entityId == entityId))).toMap
    val imageFileIds = imageFileIdToTagValues.keys.toSeq
    addOutboxEntries(remoteBoxId, transactionId, imageFileIds)
    for ((imageFileId, tagValues) <- imageFileIdToTagValues) {
      tagValues.foreach(tagValue =>
        addTagValue(imageFileId, transactionId, tagValue.tag, tagValue.value))
    }
    imageFileIds
  }

  def imageFileIdsForSeries(seriesId: Long) =
    db.withSession { implicit session =>
      metaDataDao.imageFilesForSeries(seriesId).map(_.id)
    }

  def imageFileIdsForStudy(studyId: Long) =
    db.withSession { implicit session =>
      metaDataDao.imageFilesForStudy(studyId).map(_.id)
    }

  def imageFileIdsForPatient(patientId: Long) =
    db.withSession { implicit session =>
      metaDataDao.imageFilesForPatient(patientId).map(_.id)
    }

  def addOutboxEntries(remoteBoxId: Long, transactionId: Long, imageFileIds: Seq[Long]): Unit = {
    val totalImageCount = imageFileIds.length

    db.withSession { implicit session =>
      for (sequenceNumber <- 1 to totalImageCount) {
        boxDao.insertOutboxEntry(OutboxEntry(-1, remoteBoxId, transactionId, sequenceNumber, totalImageCount, imageFileIds(sequenceNumber - 1), false))
      }
    }
  }

  def addTagValue(imageFileId: Long, transactionId: Long, tag: Int, value: String) =
    db.withSession { implicit session =>
      boxDao.insertTransactionTagValue(
        TransactionTagValue(-1, imageFileId, transactionId, tag, value))
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

  def removeTransactionTagValuesForTransactionId(transactionId: Long) = {
    db.withSession { implicit session =>
      boxDao.removeTransactionTagValuesByTransactionId(transactionId)
    }
  }

}

object BoxServiceActor {
  def props(dbProps: DbProps, storage: Path, apiBaseURL: String): Props = Props(new BoxServiceActor(dbProps, storage, apiBaseURL))
}