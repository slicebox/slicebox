package se.vgregion.box

import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import BoxProtocol._
import scala.concurrent.duration.DurationInt
import spray.client.pipelining._
import org.dcm4che3.data.Attributes
import spray.http.HttpData
import java.nio.file.Path
import spray.http.HttpRequest
import scala.concurrent.Future
import spray.http.HttpResponse
import se.vgregion.app.DbProps

class BoxPushActor(box: Box, dbProps: DbProps, storage: Path) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new BoxDAO(dbProps.driver)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val pipeline = sendReceive

  def pushImagePipeline(transactionId: Long, sequenceNumber: Long, totalImageCount: Long, fileName: String): Future[HttpResponse] = {
    val file = storage.resolve(fileName).toFile
    //if (file.isFile && file.canRead)
    pipeline(Post(s"${box.baseUrl}/image/$transactionId/$sequenceNumber/$totalImageCount", HttpData(file)))
  }

  system.scheduler.schedule(1.second, 5.seconds) {
    //self ! PollOutbox
  }

  context.become(pollOutboxState)

  def receive = LoggingReceive {
    case _ =>
  }

  def sendImageState: PartialFunction[Any, Unit] = {
    case _ =>
  }

  def pollOutboxState: PartialFunction[Any, Unit] = {
    case _ =>
      db.withSession { implicit session =>
        dao.nextOutboxEntryForRemoteBoxId(box.id).foreach(entry => {
          pushImagePipeline(entry.transactionId, entry.sequenceNumber, entry.totalImageCount, "")
        })
      }
  }
}

object BoxPushActor {
  def props(box: Box, dbProps: DbProps, storage: Path): Props = Props(new BoxPushActor(box, dbProps, storage))
}