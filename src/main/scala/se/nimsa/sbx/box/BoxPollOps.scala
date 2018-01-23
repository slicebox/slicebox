package se.nimsa.sbx.box

import akka.actor.Scheduler
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Compression, Flow, GraphDSL, MergePreferred, RunnableGraph, Sink}
import akka.util.ByteString
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import org.dcm4che3.io.DicomStreamException
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol.{IncomingUpdated, OutgoingTransactionImage}
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.MetaDataAdded

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait BoxPollOps extends BoxStreamBase with BoxJsonFormats with PlayJsonSupport {

  protected val n = 200
  protected val parallelism = 8

  val killSwitch: Graph[FlowShape[Tick, Tick], UniqueKillSwitch] = KillSwitches.single[Tick]

  implicit lazy val scheduler: Scheduler = system.scheduler

  protected def storeDicomData(bytesSource: scaladsl.Source[ByteString, _], source: Source): Future[MetaDataAdded]
  protected def updateIncomingTransaction(transactionImage: OutgoingTransactionImage, imageId: Long, overwrite: Boolean): Future[IncomingUpdated]

  case class Tick()
  protected val tick = Tick()

  lazy val getPool: Flow[(HttpRequest, OutgoingTransactionImage), (Try[HttpResponse], OutgoingTransactionImage), _] = pool[OutgoingTransactionImage]
  lazy val donePool: Flow[(HttpRequest, (OutgoingTransactionImage, Option[MetaDataAdded])), (Try[HttpResponse], (OutgoingTransactionImage, Option[MetaDataAdded])), _] = pool[(OutgoingTransactionImage, Option[MetaDataAdded])]

  lazy val puller: RunnableGraph[KillSwitch] = RunnableGraph.fromGraph(GraphDSL.create(killSwitch) {
    implicit b => switch =>

      import GraphDSL.Implicits._

      val ticker = scaladsl.Source.tick(retryInterval, retryInterval, tick)
        .detach

      val merge = b.add(MergePreferred[Tick](1))
      val bcast = b.add(Broadcast[Try[immutable.Seq[OutgoingTransactionImage]]](2))

      val tryBatch = (_: Any) => pullBatch().map(Success.apply).recover { case t: Throwable => Failure(t) }
      val batchFlow = Flow[Tick].mapAsync(1)(tryBatch)

      val onlineAndFetching = Flow[Try[immutable.Seq[OutgoingTransactionImage]]]
        .collect {
          case Success(images) if images.nonEmpty => tick
        }

      ticker ~> merge.in(0)
                merge           ~> switch ~> batchFlow ~> bcast ~> Sink.ignore
                merge.preferred <~ onlineAndFetching   <~ bcast

      ClosedShape
  })

  def pullBatch(): Future[immutable.Seq[OutgoingTransactionImage]] =
    scaladsl.Source
      .fromFuture(poll())
      .mapConcat(_.toList)
      .map(createGetRequest)
      .via(getPool)
      .map(checkAndLogGetRequest)
      .mapAsyncUnordered(parallelism)(storeData)
      .mapAsyncUnordered(parallelism)(createDoneRequest)
      .via(donePool)
      .mapAsyncUnordered(parallelism)(checkAndLogDoneRequest)
      .runWith(Sink.seq)

  protected def poll(): Future[Seq[OutgoingTransactionImage]] =
    singleRequest(pollRequest)
      .flatMap {
        case response if response.status == NotFound =>
          response.entity.discardBytes()
          Future.successful(immutable.Seq.empty)
        case response =>
          Unmarshal(response).to[OutgoingTransactionImage]
            .map(transactionImage => immutable.Seq(transactionImage))
            .recoverWith {
              case _: Throwable => Unmarshal(response).to[immutable.Seq[OutgoingTransactionImage]]
            }
      }

  protected def storeData(responseImage: (HttpResponse, OutgoingTransactionImage)): Future[(OutgoingTransactionImage, Option[MetaDataAdded])] = {
    val (response, transactionImage) = responseImage
    val source = Source(SourceType.BOX, box.name, box.id)
    storeDicomData(response.entity.dataBytes.via(Compression.inflate()), source)
      .map(metaData => (transactionImage, Option(metaData)))
      .recover {
        case _: DicomStreamException =>
          // assume exception is due to unsupported presentation context
          SbxLog.warn("Box", s"Ignoring image ${transactionImage.image.imageId} with disallowed presentation context.")
          (transactionImage, None)
      }
  }

  protected def checkAndLogGetRequest(responseImage: ResponseImage): (HttpResponse, OutgoingTransactionImage) = {
    responseImage match {
      case (Success(response), transactionImage) =>
        response.status.intValue match {
          case status if status >= 200 && status < 300 => (response, transactionImage)
          case _ =>
            response.discardEntityBytes()
            throw new RuntimeException(s"Failed getting outgoing transaction image with id ${transactionImage.image.id} from ${box.name}")
        }
      case (Failure(exception), transactionImage) => throw new RuntimeException(s"Failed getting outgoing transaction image with id ${transactionImage.image.id} from ${box.name}", exception)
    }
  }

  protected def checkAndLogDoneRequest(r: (Try[HttpResponse], (OutgoingTransactionImage, Option[MetaDataAdded]))): Future[OutgoingTransactionImage] =
    r match {
      case (Success(response), (transactionImage, Some(metaData))) =>
        response.status.intValue() match {
          case status if status >= 200 && status < 300 =>
            val overwrite = !metaData.imageAdded
            updateIncomingTransaction(transactionImage, metaData.image.id, overwrite)
              .map { updated =>
                system.eventStream.publish(ImageAdded(metaData.image.id, metaData.source, overwrite))

                transactionImage.copy(transaction = transactionImage.transaction.copy(
                  sentImageCount = updated.transaction.receivedImageCount))
              }
          case _ =>
            Future.failed(new RuntimeException("Received image could not be marked as sent"))
        }
      case (Failure(exception), (transactionImage, _)) =>
        Future.failed(new RuntimeException(s"Failed marking transaction image with id ${transactionImage.image.id} from ${box.name} as received", exception))
      case (_, (transactionImage, _)) =>
        Future.failed(new RuntimeException(s"Failed marking transaction image with id ${transactionImage.image.id} from ${box.name} as received"))
    }

  protected lazy val pollRequest: HttpRequest = {
    val uri = s"${box.baseUrl}/outgoing/poll?n=$n"
    HttpRequest(method = HttpMethods.GET, uri = uri, entity = HttpEntity.Empty)
  }

  protected def createGetRequest(transactionImage: OutgoingTransactionImage): (HttpRequest, OutgoingTransactionImage) = {
    val uri = s"${box.baseUrl}/outgoing?transactionid=${transactionImage.transaction.id}&imageid=${transactionImage.image.id}"
    HttpRequest(method = HttpMethods.GET, uri = uri, entity = HttpEntity.Empty) -> transactionImage
  }

  protected def createDoneRequest(tm: (OutgoingTransactionImage, Option[MetaDataAdded])): Future[(HttpRequest, (OutgoingTransactionImage, Option[MetaDataAdded]))] =
    tm match {
      case (transactionImage, _) =>
        Marshal(transactionImage).to[MessageEntity].map { entity =>
          val uri = s"${box.baseUrl}/outgoing/done"
          HttpRequest(method = HttpMethods.POST, uri = uri, entity = entity) -> tm
        }
    }


}
