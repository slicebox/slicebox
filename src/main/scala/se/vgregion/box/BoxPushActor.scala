package se.vgregion.box

import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import BoxProtocol._
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import spray.client.pipelining._
import org.dcm4che3.data.Attributes
import spray.http.HttpData
import java.nio.file.Path
import spray.http.HttpRequest
import spray.http.StatusCodes._
import scala.concurrent.Future
import spray.http.HttpResponse
import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomMetaDataDAO
import spray.http.StatusCode

class BoxPushActor(box: Box, dbProps: DbProps, storage: Path, pollInterval: FiniteDuration = 5.seconds) extends Actor {
  val log = Logging(context.system, this)
  
  case object PollOutbox
  case class FileSent(outboxEntry: OutboxEntry)
  case class FileSendFailed(outboxEntry: OutboxEntry, e: Exception)

  val db = dbProps.db
  val boxDao = new BoxDAO(dbProps.driver)
  val dicomMetaDataDao = new DicomMetaDataDAO(dbProps.driver)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  def sendFilePipeline = sendReceive

  def pushImagePipeline(outboxEntry: OutboxEntry, fileName: String): Future[HttpResponse] = {
    val file = storage.resolve(fileName).toFile
    //if (file.isFile && file.canRead)
    sendFilePipeline(Post(s"${box.baseUrl}/image/${outboxEntry.transactionId}/${outboxEntry.sequenceNumber}/${outboxEntry.totalImageCount}", HttpData(file)))
  }

  system.scheduler.schedule(100.millis, pollInterval) {
    self ! PollOutbox
  }

  def receive = LoggingReceive {
    case PollOutbox => processNextOutboxEntry
  }

  def waitForFileSentState: PartialFunction[Any, Unit] = LoggingReceive {
    case FileSent(outboxEntry) => handleFileSentForOutboxEntry(outboxEntry)
    
    case FileSendFailed(outboxEntry, exception) => handleFileSendFailedForOutboxEntry(outboxEntry, exception)
  }

  def processNextOutboxEntry(): Unit = {
    nextOutboxEntry match {
      case Some(entry) => {
        sendFileForOutboxEntry(entry)
        context.become(waitForFileSentState)
      }
      
      case None => context.unbecome
    }
  }
  
  def nextOutboxEntry: Option[OutboxEntry] =
    db.withSession { implicit session =>
      boxDao.nextOutboxEntryForRemoteBoxId(box.id)
    }
  
  def sendFileForOutboxEntry(outboxEntry: OutboxEntry) = {
    fileNameForImageId(outboxEntry.imageId) match {
      case Some(fileName) => sendFileWithName(outboxEntry, fileName)
      case None =>
        handleFileSendFailedForOutboxEntry(outboxEntry, new IllegalStateException(s"Can't process outbox entry (${outboxEntry.id}) because no image with id ${outboxEntry.imageId} was found"))
    }
  }
  
  def fileNameForImageId(imageId: Long) : Option[String] =
    db.withSession { implicit session =>
      dicomMetaDataDao.imageFileById(imageId).map(_.fileName.value)
    }

  def sendFileWithName(outboxEntry: OutboxEntry, fileName: String) = {
    pushImagePipeline(outboxEntry, fileName)
      .map(response => {
        val responseCode = response.status.intValue
        if (responseCode >= 200 && responseCode < 300)
          self ! FileSent(outboxEntry)
        else
          self ! FileSendFailed(outboxEntry, new Exception(s"File send failed with status code $responseCode"))
      })
      .recover {
        case exception: Exception =>
          self ! FileSendFailed(outboxEntry, exception)
      }
  }

  def handleFileSentForOutboxEntry(outboxEntry: OutboxEntry) = {
    log.info(s"File sent for outbox entry ${outboxEntry.id}")
    
    db.withSession { implicit session =>
      boxDao.removeOutboxEntry(outboxEntry.id)
    }

    processNextOutboxEntry
  }
  
  def handleFileSendFailedForOutboxEntry(outboxEntry: OutboxEntry, exception: Exception) = {
    log.error(exception, s"Failed to send file to box: ${outboxEntry.id}")
    
    db.withSession { implicit session =>
      boxDao.markOutboxTransactionAsFailed(outboxEntry.transactionId)
    }

    processNextOutboxEntry
  }
}

object BoxPushActor {
  def props(box: Box, dbProps: DbProps, storage: Path): Props = Props(new BoxPushActor(box, dbProps, storage))
}