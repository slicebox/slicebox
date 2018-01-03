package se.nimsa.sbx.box

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.stream.scaladsl.{Compression, Flow, GraphDSL, Keep, MergePreferred, RestartFlow, Sink, Source, SourceQueueWithComplete}
import akka.util.ByteString
import akka.{Done, NotUsed}
import se.nimsa.sbx.app.GeneralProtocol.{Destination, DestinationType, ImagesSent}
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.log.SbxLog

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}

trait BoxPushOps {

  implicit val system: ActorSystem
  implicit val ec: ExecutionContextExecutor
  implicit val materializer: Materializer

  val parallelism: Int

  def pendingOutgoingImagesForTransaction(transaction: OutgoingTransaction): Future[Source[OutgoingTransactionImage, NotUsed]]
  def outgoingTagValuesForImage(transactionImage: OutgoingTransactionImage): Future[Seq[OutgoingTagValue]]
  def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage): Future[OutgoingTransactionImage]
  def setOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit]
  def getOutgoingImageIdsForTransaction(transaction: OutgoingTransaction): Future[Seq[Long]]
  def anonymizedDicomData(transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Source[ByteString, NotUsed]
  def sliceboxRequest(method: HttpMethod, uri: String, entity: RequestEntity): Future[HttpResponse]

  def pushTransaction(box: Box, transaction: OutgoingTransaction): (Future[Done], KillSwitch) = {
    val (recycleQueue, recycleSource) = createQueue[OutgoingTransactionImage](10 * parallelism)

    val (killSwitch, futureResult) = Source.fromFutureSource(pendingOutgoingImagesForTransaction(transaction))
      .via(mergePreferred(Source.fromFutureSource(recycleSource)))

      .via(RestartFlow.withBackoff(1.second, 15.seconds, 0.2) { () =>

        Flow[OutgoingTransactionImage]
          .mapAsync(parallelism) { transactionImage =>
            outgoingTagValuesForImage(transactionImage)
              .flatMap { tagValues =>
                pushImage(box, transactionImage, tagValues)
                  .flatMap { httpResponse =>
                    httpResponse.status.intValue() match {
                      case status if status >= 200 && status < 300 =>
                        handleFileSentForOutgoingTransaction(box, transactionImage)
                      case status =>
                        Unmarshal(httpResponse).to[String].flatMap { errorMessage =>
                          SbxLog.warn("Box", s"Failed pushing image ${transactionImage.image.imageId}, got status code $status and message $errorMessage. Retrying later.")
                          val exception = new RuntimeException(errorMessage)
                          handleFileSendFailedForOutgoingTransaction(transactionImage, exception)
                            .map { _ =>
                              recycleQueue.offer(transactionImage)
                              throw exception
                              transactionImage
                            }
                        }
                    }
                  }
                  .recoverWith {
                    case exception: Exception =>
                      handleFileSendFailedForOutgoingTransaction(transactionImage, exception)
                        .map { _ =>
                          recycleQueue.offer(transactionImage)
                          throw exception
                        }
                  }
              }
          }

      })
      .map { updatedTransactionImage =>
        if (updatedTransactionImage.transaction.sentImageCount == updatedTransactionImage.transaction.totalImageCount)
          recycleQueue.complete()
        updatedTransactionImage
      }
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.ignore)(Keep.both)
      .mapMaterializedValue {
        case (switch, futureDone) =>
          val doneWithCleanup = futureDone.flatMap(_ => handleTransactionFinished(box, transaction).map(_ => Done))
          (switch, doneWithCleanup)
      }
      .run()

    (futureResult, killSwitch)
  }

  def pushImage(box: Box, transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Future[HttpResponse] = {
    val source = anonymizedDicomData(transactionImage, tagValues)
      .via(Compression.deflate)
    val uri = s"${box.baseUrl}/image?transactionid=${transactionImage.transaction.id}&sequencenumber=${transactionImage.image.sequenceNumber}&totalimagecount=${transactionImage.transaction.totalImageCount}"
    sliceboxRequest(HttpMethods.POST, uri, HttpEntity(ContentTypes.`application/octet-stream`, source))
  }

  def handleFileSentForOutgoingTransaction(box: Box, transactionImage: OutgoingTransactionImage): Future[OutgoingTransactionImage] =
    updateOutgoingTransaction(transactionImage)

  def handleFileSendFailedForOutgoingTransaction(transactionImage: OutgoingTransactionImage, exception: Exception): Future[OutgoingTransactionImage] =
    setOutgoingTransactionStatus(transactionImage.transaction, TransactionStatus.WAITING).map(_ => transactionImage)

  def handleTransactionFinished(box: Box, transaction: OutgoingTransaction): Future[Unit] =
    getOutgoingImageIdsForTransaction(transaction)
      .flatMap { imageIds =>
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

  def createQueue[A](bufferSize: Int): (SourceQueueWithComplete[A], Future[Source[A, NotUsed]]) =
    Source
      .queue[A](bufferSize, OverflowStrategy.backpressure)
      .prefixAndTail(0)
      .map(_._2)
      .toMat(Sink.head)(Keep.both)
      .run()

  def mergePreferred[A, M](g: Graph[SourceShape[A], M]): Graph[FlowShape[A, A], M] =
    GraphDSL.create(g) { implicit b =>
      r =>
        import akka.stream.scaladsl.GraphDSL.Implicits._

        val merge = b.add(MergePreferred[A](1))
        r ~> merge.preferred
        FlowShape(merge.in(0), merge.out)
    }

}
