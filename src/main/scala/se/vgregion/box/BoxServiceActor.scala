package se.vgregion.box

import se.vgregion.app.DbProps
import akka.actor.Actor
import akka.event.LoggingReceive
import se.vgregion.box.BoxProtocol._
import akka.actor.Props
import akka.actor.PoisonPill
import java.util.UUID
import akka.actor.Status.Failure
import se.vgregion.util.ExceptionCatching
import java.nio.file.Path
import scala.math.abs

class BoxServiceActor(dbProps: DbProps, storage: Path, host: String, port: Int) extends Actor with ExceptionCatching {

  val db = dbProps.db
  val dao = new BoxDAO(dbProps.driver)

  setupDb()
  setupBoxes()

  def receive = LoggingReceive {

    case msg: BoxRequest =>

      catchAndReport {

        msg match {

          case GenerateBoxBaseUrl(remoteBoxName) =>
            val token = UUID.randomUUID().toString()
            val baseUrl = s"http://$host:$port/api/box/$token"
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
            pollBoxByToken(token).foreach(box => {
              updateInbox(box.id, transactionId, sequenceNumber, totalImageCount)
            })
            
            // TODO: what should we do if no box was found for token?
            
            sender ! InboxUpdated(token, transactionId, sequenceNumber, totalImageCount)
            
          case PollOutbox(token) =>
            pollBoxByToken(token).foreach(box => {
              nextOutboxEntry(box.id) match {
                case Some(outboxEntry) => sender ! outboxEntry
                case None              => sender ! OutboxEmpty
              }
            })
            
            // TODO: what should we do if no box was found for token?
            
          case SendImageToRemoteBox(remoteBoxId, imageId) =>
            addOutboxEntry(remoteBoxId, imageId)
            sender ! ImageSent(remoteBoxId, imageId)
            
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
            val outboxEntries = getOutboxFromDb().map { outboxEntry =>
              boxById(outboxEntry.remoteBoxId) match {
                case Some(box) => OutboxEntryInfo(outboxEntry.id, box.name, outboxEntry.transactionId, outboxEntry.sequenceNumber, outboxEntry.totalImageCount, outboxEntry.imageId, outboxEntry.failed)
                case None      => OutboxEntryInfo(outboxEntry.id, "" + outboxEntry.remoteBoxId, outboxEntry.transactionId, outboxEntry.sequenceNumber, outboxEntry.totalImageCount, outboxEntry.imageId, outboxEntry.failed)
              }
            }
            sender ! Outbox(outboxEntries)
            
          case RemoveOutboxEntry(outboxEntryId) =>
            removeOutboxEntryFromDb(outboxEntryId)
            sender ! OutboxEntryRemoved(outboxEntryId)
        }

      }

  }

  def setupDb(): Unit =
    db.withSession { implicit session =>
      dao.create
    }

  def teardownDb(): Unit =
    db.withSession { implicit session =>
      dao.drop
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
      case BoxSendMethod.POLL => // No action needed
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
      dao.insertBox(box)
    }

  def boxById(boxId: Long): Option[Box] =
    db.withSession { implicit session =>
      dao.boxById(boxId)
    }
  
  def pollBoxByToken(token: String): Option[Box] =
    db.withSession { implicit session =>
      dao.pollBoxByToken(token)
    }

  def pushBoxByBaseUrl(baseUrl: String): Option[Box] =
    db.withSession { implicit session =>
      dao.pushBoxByBaseUrl(baseUrl)
    }

  def removeBoxFromDb(boxId: Long) =
    db.withSession { implicit session =>
      dao.removeBox(boxId)
    }

  def getBoxesFromDb(): Seq[Box] =
    db.withSession { implicit session =>
      dao.listBoxes
    }

  def tokenIsValid(token: String): Boolean =
    db.withSession { implicit session =>
      println(dao.listBoxes)
      println(token)
      dao.pollBoxByToken(token).isDefined
    }
  
  def nextOutboxEntry(boxId: Long): Option[OutboxEntry] =
    db.withSession { implicit session =>
      dao.nextOutboxEntryForRemoteBoxId(boxId)
    }
  
  def updateInbox(remoteBoxId: Long, transactionId: Long, sequenceNumber: Long, totalImageCount: Long): Unit =
    db.withSession { implicit session =>
      dao.updateInbox(remoteBoxId, transactionId, sequenceNumber, totalImageCount)
    }
  
  def addOutboxEntry(remoteBoxId: Long, imageId: Long): Unit = {
    val transactionId = generateTransactionId()
    db.withSession { implicit session =>
      dao.insertOutboxEntry(OutboxEntry(-1, remoteBoxId, transactionId, 1, 1, imageId, false))
    }
  }
  
  def generateTransactionId(): Long =
    // Must be a positive number for the generated id to work in URLs which is very strange
    // Maybe switch to using Strings as transaction id?
    abs(UUID.randomUUID().getMostSignificantBits())
    
  def outboxEntryByTransactionIdAndSequenceNumber(remoteBoxId: Long, transactionId: Long, sequenceNumber: Long): Option[OutboxEntry] =
    db.withSession { implicit session =>
      dao.outboxEntryByTransactionIdAndSequenceNumber(remoteBoxId, transactionId, sequenceNumber)
    }
  
  def removeOutboxEntryFromDb(outboxEntryId: Long) =
    db.withSession { implicit session =>
      dao.removeOutboxEntry(outboxEntryId)
    }
  
  def getInboxFromDb() =
    db.withSession { implicit session =>
      dao.listInboxEntries
    }
  
  def getOutboxFromDb() =
    db.withSession { implicit session =>
      dao.listOutboxEntries
    }
}

object BoxServiceActor {
  def props(dbProps: DbProps, storage: Path, host: String, port: Int): Props = Props(new BoxServiceActor(dbProps, storage, host, port))
}