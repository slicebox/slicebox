package se.nimsa.sbx.box

import akka.actor.{ActorSystem, Scheduler}
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.stream.scaladsl.{Compression, Keep, Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import se.nimsa.sbx.app.GeneralProtocol.{Destination, DestinationType, ImagesSent}
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.util.FutureUtil.retry

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

trait BoxPushOps {

  implicit val system: ActorSystem
  implicit val materializer: Materializer
  implicit val ec: ExecutionContext
  implicit val scheduler: Scheduler

  val parallelism: Int
  val streamChunkSize: Long

  val minBackoff: FiniteDuration = 1.second
  val maxBackoff: FiniteDuration = 15.seconds

  def pendingOutgoingImagesForTransaction(transaction: OutgoingTransaction): Future[Source[OutgoingTransactionImage, NotUsed]]
  def outgoingTagValuesForImage(transactionImage: OutgoingTransactionImage): Future[Seq[OutgoingTagValue]]
  def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage]
  def setOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit]
  def anonymizedDicomData(transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Source[ByteString, NotUsed]
  def sliceboxRequest(method: HttpMethod, uri: String, entity: RequestEntity): Future[HttpResponse]

  def pushTransaction(box: Box, transaction: OutgoingTransaction): (Future[Done], KillSwitch) =
    Source
      .fromFutureSource(pendingOutgoingImagesForTransaction(transaction))
      .mapAsyncUnordered(parallelism) { transactionImage =>
        retry(minBackoff, maxBackoff, 0.2) { case _: Throwable => true } {
          outgoingTagValuesForImage(transactionImage)
            .flatMap { tagValues =>
              pushImage(box, transactionImage, tagValues)
                .recoverWith {
                  case exception: Exception =>
                    handleFileSendFailedForOutgoingTransaction(transactionImage, exception)
                      .map(_ => throw exception)
                }
                .flatMap { httpResponse =>
                  httpResponse.status.intValue() match {
                    case status if status >= 200 && status < 300 =>
                      Future.successful(transactionImage)
                    case status =>
                      Unmarshal(httpResponse).to[String].flatMap { errorMessage =>
                        SbxLog.warn("Box", s"Failed pushing image ${transactionImage.image.imageId}, got status code $status and message $errorMessage. Retrying later.")
                        val exception = new RuntimeException(errorMessage)
                        handleFileSendFailedForOutgoingTransaction(transactionImage, exception)
                          .map(_ => throw exception)
                      }
                  }
                }
            }
        }
      }
      .zipWithIndex
      .mapAsync(1) {
        case (transactionImage, index) => handleFileSentForOutgoingTransaction(transactionImage, index + 1 + transaction.sentImageCount)
      }
      .map(_.image.imageId)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.seq)(Keep.both)
      .mapMaterializedValue {
        case (switch, futureImageIds) =>
          val futureDone = futureImageIds
            .flatMap(imageIds => handleTransactionFinished(box, transaction, imageIds))
            .map(_ => Done)
          (futureDone, switch)
      }
      .run()

  def pushImage(box: Box, transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Future[HttpResponse] = {
    val source = anonymizedDicomData(transactionImage, tagValues)
      .batchWeighted(streamChunkSize, _.length, identity)(_ ++ _)
      .via(Compression.deflate)
    val uri = s"${box.baseUrl}/image?transactionid=${transactionImage.transaction.id}&sequencenumber=${transactionImage.image.sequenceNumber}&totalimagecount=${transactionImage.transaction.totalImageCount}"
    sliceboxRequest(HttpMethods.POST, uri, HttpEntity(ContentTypes.`application/octet-stream`, source))
  }

  def handleFileSentForOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] =
    updateOutgoingTransaction(transactionImage, sentImageCount)

  def handleFileSendFailedForOutgoingTransaction(transactionImage: OutgoingTransactionImage, exception: Exception): Future[OutgoingTransactionImage] =
    setOutgoingTransactionStatus(transactionImage.transaction, TransactionStatus.WAITING).map(_ => transactionImage)

  def handleTransactionFinished(box: Box, transaction: OutgoingTransaction, imageIds: Seq[Long]): Future[Unit] =
    setOutgoingTransactionStatus(transaction, TransactionStatus.FINISHED)
      .flatMap { _ =>
        val uri = s"${box.baseUrl}/status?transactionid=${transaction.id}"
        sliceboxRequest(HttpMethods.PUT, uri, HttpEntity(TransactionStatus.FINISHED.toString))
          .recover {
            case _: Exception =>
              SbxLog.warn("Box", s"Unable to set remote status of transaction ${transaction.id} to FINISHED.")
          }
          .map { _ =>
            SbxLog.info("Box", s"Finished sending ${transaction.totalImageCount} images to box ${box.name}")
            system.eventStream.publish(ImagesSent(Destination(DestinationType.BOX, box.name, box.id), imageIds))
          }
      }

}
