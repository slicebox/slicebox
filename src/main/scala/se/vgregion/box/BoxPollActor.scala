package se.vgregion.box

import akka.event.LoggingReceive
import akka.actor.Actor
import spray.client.pipelining._
import scala.concurrent.duration.DurationInt
import akka.event.Logging
import akka.actor.Props
import BoxProtocol._
import se.vgregion.dicom.DicomProtocol.DatasetReceived
import se.vgregion.app.DbProps
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import spray.http.HttpResponse
import se.vgregion.app.JsonFormats
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.httpx.unmarshalling.FromResponseUnmarshaller
import spray.http.StatusCodes
import se.vgregion.dicom.DicomUtil

class BoxPollActor(box: Box, dbProps: DbProps, pollInterval: FiniteDuration = 5.seconds) extends Actor with JsonFormats {
  val log = Logging(context.system, this)

  case object PollRemoteBox
  case object RemoteOutboxEmpty
  case class RemoteOutboxEntryFound(remoteOutboxEntry: OutboxEntry)
  case class PollRemoteBoxFailed(e: Throwable)
  case class RemoteOutboxFileFetched(remoteOutboxEntry: OutboxEntry)
  case class FetchFileFailed(e: Throwable)
  
  val db = dbProps.db
  val boxDao = new BoxDAO(dbProps.driver)
  
  implicit val system = context.system
  implicit val ec = context.dispatcher
  
  def convertOption[T](implicit unmarshaller:FromResponseUnmarshaller[T]): Future[HttpResponse] => Future[Option[T]] = 
      (futureResponse: Future[HttpResponse]) => futureResponse.map {response =>
        if (response.status == StatusCodes.NotFound) None
          else Some(unmarshal[T](unmarshaller)(response))
      }

  def sendRequestToRemoteBoxPipeline = sendReceive
  def pollRemoteBoxOutboxPipeline = sendRequestToRemoteBoxPipeline ~> convertOption[OutboxEntry]
  
  
  def sendPollRequestToRemoteBox: Future[Option[OutboxEntry]] = {
    pollRemoteBoxOutboxPipeline(Get(s"${box.baseUrl}/outbox/poll"))
  }
  
  def getRemoteOutboxFile(remoteOutboxEntry: OutboxEntry): Future[HttpResponse] = {
    sendRequestToRemoteBoxPipeline(Get(s"${box.baseUrl}/outbox?transactionId=${remoteOutboxEntry.transactionId}&sequenceNumber=${remoteOutboxEntry.sequenceNumber}"))
  }

  system.scheduler.schedule(100.millis, pollInterval) {
    self ! PollRemoteBox
  }
  
  def receive = LoggingReceive {
    case PollRemoteBox => pollRemoteBox
  }
  
  def waitForPollRemoteOutboxState: PartialFunction[Any, Unit] = LoggingReceive {
    case RemoteOutboxEmpty => {
      log.debug("Remote outbox is empty")
      context.unbecome
    }
    
    case RemoteOutboxEntryFound(remoteOutboxEntry) => {
      log.debug(s"Received outbox entry ${remoteOutboxEntry}")
      fetchFileForRemoteOutboxEntry(remoteOutboxEntry)
      context.become(waitForFileFetchedState)
    }

    case PollRemoteBoxFailed(exception) => {
      log.error(exception, "Failed to poll remote outbox")
      context.unbecome
    }
  }
  
  def waitForFileFetchedState: PartialFunction[Any, Unit] = LoggingReceive {
    case RemoteOutboxFileFetched(remoteOutboxEntry) => {
      updateInbox(box.id, remoteOutboxEntry.transactionId, remoteOutboxEntry.sequenceNumber, remoteOutboxEntry.totalImageCount)
      context.unbecome
    }

    case PollRemoteBoxFailed(exception) => {
      log.error(exception, "Failed to fetch remote outbox file")
      context.unbecome
    }
  }
  
  def pollRemoteBox(): Unit = {
    sendPollRequestToRemoteBox
      .map(outboxEntryMaybe =>
        outboxEntryMaybe match {
          case Some(outboxEntry) => self ! RemoteOutboxEntryFound(outboxEntry)
          case None              => self ! RemoteOutboxEmpty
        }
      )
      .recover {
        case exception: Exception =>
          self ! PollRemoteBoxFailed(exception)
      }
    
    context.become(waitForPollRemoteOutboxState)
  }
    
  def fetchFileForRemoteOutboxEntry(remoteOutboxEntry: OutboxEntry): Unit = {
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
  }
  
  def updateInbox(remoteBoxId: Long, transactionId: Long, sequenceNumber: Long, totalImageCount: Long): Unit =
    db.withSession { implicit session =>
      boxDao.updateInbox(remoteBoxId, transactionId, sequenceNumber, totalImageCount)
    }
}

object BoxPollActor {
  def props(box: Box, dbProps: DbProps): Props = Props(new BoxPollActor(box, dbProps))
}