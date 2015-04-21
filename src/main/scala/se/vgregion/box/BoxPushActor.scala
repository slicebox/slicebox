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
import se.vgregion.dicom.DicomUtil._
import se.vgregion.dicom.DicomAnonymization._
import java.io.ByteArrayOutputStream
import se.vgregion.log.LogProtocol._
import java.util.Date
import akka.actor.ReceiveTimeout

class BoxPushActor(box: Box,
                   dbProps: DbProps,
                   storage: Path,
                   pollInterval: FiniteDuration = 5.seconds,
                   receiveTimeout: FiniteDuration = 1.minute) extends Actor {

  import BoxPushActor._

  val log = Logging(context.system, this)

  val db = dbProps.db
  val boxDao = new BoxDAO(dbProps.driver)
  val dicomMetaDataDao = new DicomMetaDataDAO(dbProps.driver)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  def sendFilePipeline = sendReceive

  def pushImagePipeline(outboxEntry: OutboxEntry, fileName: String, tagValues: Seq[TransactionTagValue]): Future[HttpResponse] = {
    val path = storage.resolve(fileName)
    val dataset = loadDataset(path, true)
    val anonymizedDataset = anonymizeDataset(dataset)
    applyTagValues(anonymizedDataset, tagValues)
    val bytes = toByteArray(anonymizedDataset)
    sendFilePipeline(Post(s"${box.baseUrl}/image?transactionid=${outboxEntry.transactionId}&sequencenumber=${outboxEntry.sequenceNumber}&totalimagecount=${outboxEntry.totalImageCount}", HttpData(bytes)))
  }

  val poller = system.scheduler.schedule(pollInterval, pollInterval) {
    self ! PollOutbox
  }

  override def postStop() =
    poller.cancel()

  context.setReceiveTimeout(receiveTimeout)

  def receive = LoggingReceive {
    case PollOutbox =>
      processNextOutboxEntry
  }

  def processNextOutboxEntry(): Unit =
    nextOutboxEntry match {
      case Some(entry) =>
        context.become(waitForFileSentState)
        sendFileForOutboxEntry(entry)

      case None =>
        context.unbecome
    }

  def waitForFileSentState: Receive = LoggingReceive {

    case FileSent(outboxEntry) =>
      handleFileSentForOutboxEntry(outboxEntry)

    case FileSendFailed(outboxEntry, exception) =>
      handleFileSendFailedForOutboxEntry(outboxEntry, exception)

    case ReceiveTimeout =>
      log.error("Processing next outbox entry timed out")
      context.unbecome
  }

  def nextOutboxEntry: Option[OutboxEntry] =
    db.withSession { implicit session =>
      boxDao.nextOutboxEntryForRemoteBoxId(box.id)
    }

  def sendFileForOutboxEntry(outboxEntry: OutboxEntry) =
    fileNameForImageFileId(outboxEntry.imageFileId) match {
      case Some(fileName) =>
        val transactionTagValues = transactionTagValuesForTransactionId(outboxEntry.transactionId)
        sendFileWithName(outboxEntry, fileName, transactionTagValues)
      case None =>
        handleFilenameLookupFailedForOutboxEntry(outboxEntry, new IllegalStateException(s"Can't process outbox entry (${outboxEntry.id}) because no image with id ${outboxEntry.imageFileId} was found"))
    }

  def fileNameForImageFileId(imageFileId: Long): Option[String] =
    db.withSession { implicit session =>
      dicomMetaDataDao.imageFileById(imageFileId).map(_.fileName.value)
    }

  def transactionTagValuesForTransactionId(transactionId: Long): Seq[TransactionTagValue] =
    db.withSession { implicit session =>
      boxDao.transactionTagValuesByTransactionId(transactionId)
    }

  def sendFileWithName(outboxEntry: OutboxEntry, fileName: String, tagValues: Seq[TransactionTagValue]) = {
    pushImagePipeline(outboxEntry, fileName, tagValues)
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
    log.debug(s"File sent for outbox entry ${outboxEntry.id}")

    db.withSession { implicit session =>
      boxDao.removeOutboxEntry(outboxEntry.id)
    }

    if (outboxEntry.sequenceNumber == outboxEntry.totalImageCount) {
      context.system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.INFO, "Box", "Send completed.")))
      removeTransactionTagValuesForTransactionId(outboxEntry.transactionId)
    }

    processNextOutboxEntry
  }

  def handleFileSendFailedForOutboxEntry(outboxEntry: OutboxEntry, exception: Exception) = {
    log.debug(s"Failed to send file to box ${outboxEntry.id}: " + exception.getMessage)
    context.unbecome
  }

  def handleFilenameLookupFailedForOutboxEntry(outboxEntry: OutboxEntry, exception: Exception) = {
    log.error(s"Failed to send file to box ${outboxEntry.id}: " + exception.getMessage)

    db.withSession { implicit session =>
      boxDao.markOutboxTransactionAsFailed(box.id, outboxEntry.transactionId)
    }

    context.unbecome
  }

  def removeTransactionTagValuesForTransactionId(transactionId: Long) = {
    db.withSession { implicit session =>
      boxDao.removeTransactionTagValuesByTransactionId(transactionId)
    }
  }

}

object BoxPushActor {

  def props(box: Box,
            dbProps: DbProps,
            storage: Path,
            pollInterval: FiniteDuration = 5.seconds,
            receiveTimeout: FiniteDuration = 1.minute): Props =
    Props(new BoxPushActor(box, dbProps, storage, pollInterval, receiveTimeout))

  case object PollOutbox
  case class FileSent(outboxEntry: OutboxEntry)
  case class FileSendFailed(outboxEntry: OutboxEntry, e: Exception)

}