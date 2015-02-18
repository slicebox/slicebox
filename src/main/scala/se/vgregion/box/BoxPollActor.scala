package se.vgregion.box

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import spray.client.pipelining._
import spray.http.HttpResponse
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.httpx.marshalling.marshal
import spray.httpx.unmarshalling.FromResponseUnmarshaller
import se.vgregion.app.DbProps
import se.vgregion.app.JsonFormats
import se.vgregion.dicom.DicomProtocol.DatasetReceived
import se.vgregion.dicom.DicomUtil
import BoxProtocol.Box
import BoxProtocol.OutboxEntry
import akka.actor.ReceiveTimeout

class BoxPollActor(
  box: Box,
  dbProps: DbProps,
  pollStartDelay: FiniteDuration = 100.millis,
  pollInterval: FiniteDuration = 5.seconds,
  receiveTimeout: FiniteDuration = 1.minute) extends Actor with JsonFormats {

  import BoxPollActor._

  val log = Logging(context.system, this)

  val db = dbProps.db
  val boxDao = new BoxDAO(dbProps.driver)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  def convertOption[T](implicit unmarshaller: FromResponseUnmarshaller[T]): Future[HttpResponse] => Future[Option[T]] =
    (futureResponse: Future[HttpResponse]) => futureResponse.map { response =>
      if (response.status == StatusCodes.NotFound) None
      else Some(unmarshal[T](unmarshaller)(response))
    }

  def sendRequestToRemoteBoxPipeline = sendReceive
  def pollRemoteBoxOutboxPipeline = sendRequestToRemoteBoxPipeline ~> convertOption[OutboxEntry]

  def sendPollRequestToRemoteBox: Future[Option[OutboxEntry]] =
    pollRemoteBoxOutboxPipeline(Get(s"${box.baseUrl}/outbox/poll"))

  def getRemoteOutboxFile(remoteOutboxEntry: OutboxEntry): Future[HttpResponse] =
    sendRequestToRemoteBoxPipeline(Get(s"${box.baseUrl}/outbox?transactionId=${remoteOutboxEntry.transactionId}&sequenceNumber=${remoteOutboxEntry.sequenceNumber}"))

  // We don't need to wait for done message to be sent since it is not critical that it is received by the remote box
  def sendRemoteOutboxFileCompleted(remoteOutboxEntry: OutboxEntry): Unit =
    marshal(remoteOutboxEntry) match {
      case Right(entity) => sendRequestToRemoteBoxPipeline(Post(s"${box.baseUrl}/outbox/done", entity))
      case Left(e)       => log.error(e, s"Failed to send done message to remote box (${box.name},${remoteOutboxEntry.transactionId},${remoteOutboxEntry.sequenceNumber})")
    }

  val poller = system.scheduler.schedule(pollStartDelay, pollInterval) {
    self ! PollRemoteBox
  }

  context.setReceiveTimeout(receiveTimeout)

  override def postStop() =
    poller.cancel()
  
  def receive = LoggingReceive {
    case PollRemoteBox => pollRemoteBox
  }

  def waitForPollRemoteOutboxState: PartialFunction[Any, Unit] = LoggingReceive {
    case RemoteOutboxEmpty =>
      log.debug("Remote outbox is empty")
      context.unbecome

    case RemoteOutboxEntryFound(remoteOutboxEntry) =>
      log.debug(s"Received outbox entry ${remoteOutboxEntry}")

      context.become(waitForFileFetchedState)
      fetchFileForRemoteOutboxEntry(remoteOutboxEntry)

    case PollRemoteBoxFailed(exception) =>
      log.error(exception, "Failed to poll remote outbox")
      context.unbecome

    case ReceiveTimeout =>
      log.error("Polling sequence timed out while waiting for remote outbox state")
      context.unbecome
  }

  def waitForFileFetchedState: PartialFunction[Any, Unit] = LoggingReceive {
    case RemoteOutboxFileFetched(remoteOutboxEntry) =>
      updateInbox(box.id, remoteOutboxEntry.transactionId, remoteOutboxEntry.sequenceNumber, remoteOutboxEntry.totalImageCount)
      sendRemoteOutboxFileCompleted(remoteOutboxEntry)
      context.unbecome

    case PollRemoteBoxFailed(exception) =>
      log.error(exception, "Failed to fetch remote outbox file")
      context.unbecome

    case ReceiveTimeout =>
      log.error("Polling sequence timed out while fetching data")
      context.unbecome
  }

  def pollRemoteBox(): Unit = {
    context.become(waitForPollRemoteOutboxState)

    sendPollRequestToRemoteBox
      .map(outboxEntryMaybe =>
        outboxEntryMaybe match {
          case Some(outboxEntry) => self ! RemoteOutboxEntryFound(outboxEntry)
          case None              => self ! RemoteOutboxEmpty
        })
      .recover {
        case exception: Exception =>
          self ! PollRemoteBoxFailed(exception)
      }
  }

  def fetchFileForRemoteOutboxEntry(remoteOutboxEntry: OutboxEntry): Unit =
    getRemoteOutboxFile(remoteOutboxEntry)
      .map(response => {
        val dataset = DicomUtil.loadDataset(response.entity.data.toByteArray, true)

        context.system.eventStream.publish(DatasetReceived(dataset))

        self ! RemoteOutboxFileFetched(remoteOutboxEntry)
      })
      .recover {
        case exception: Exception =>
          self ! FetchFileFailed(exception)
      }

  def updateInbox(remoteBoxId: Long, transactionId: Long, sequenceNumber: Long, totalImageCount: Long): Unit =
    db.withSession { implicit session =>
      boxDao.updateInbox(remoteBoxId, transactionId, sequenceNumber, totalImageCount)
    }
  
}

object BoxPollActor {
  def props(box: Box, dbProps: DbProps): Props = Props(new BoxPollActor(box, dbProps))

  case object PollRemoteBox
  case object RemoteOutboxEmpty
  case class RemoteOutboxEntryFound(remoteOutboxEntry: OutboxEntry)
  case class PollRemoteBoxFailed(e: Throwable)
  case class RemoteOutboxFileFetched(remoteOutboxEntry: OutboxEntry)
  case class FetchFileFailed(e: Throwable)
  case object PollSequenceTimeout

}