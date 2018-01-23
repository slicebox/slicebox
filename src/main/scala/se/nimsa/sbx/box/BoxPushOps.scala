package se.nimsa.sbx.box

import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.{Done, NotUsed}
import se.nimsa.sbx.app.GeneralProtocol.{Destination, DestinationType, ImagesSent}
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.log.SbxLog

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait BoxPushOps extends BoxStreamBase {

  import GraphDSL.Implicits._

  protected def pendingOutgoingImagesForTransaction(transaction: OutgoingTransaction): Future[Source[OutgoingTransactionImage, NotUsed]]
  protected def outgoingTagValuesForImage(transactionImage: OutgoingTransactionImage): Future[Seq[OutgoingTagValue]]
  protected def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage]
  protected def setOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit]
  protected def anonymizedDicomData(transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Source[ByteString, NotUsed]

  lazy val pushPool: Flow[(HttpRequest, OutgoingTransactionImage), (Try[HttpResponse], OutgoingTransactionImage), _] = pool[OutgoingTransactionImage]

  /**
    * Push the images of the input transaction to the remote box.
    * @return a `Future` which completes when all images have been sent and a `KillSwitch` which can be used to cancel
    *         the transaction.
    */
  def pushTransaction(transaction: OutgoingTransaction): (Future[Done], KillSwitch) =
    Source
      .fromFutureSource(pendingOutgoingImagesForTransaction(transaction))
      .via(pushWithRetryAndCompletionFlow(transaction))
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
  //TODO if pushing box is shut down and transfer is resumed later, all imageIDs will not be collected. Emit all ids in transaction as before

  /**
    * Push images to a remote with retry, and add graph nodes which make sure the flow does not complete until all
    * images have been sent. This protects from the rare but possible case where the one of the last images fail and
    * require retry. Without attention to this, the upstream would complete along with the rest of the graph, making
    * retry of these last images impossible.
    */
  protected def pushWithRetryAndCompletionFlow(transaction: OutgoingTransaction): Flow[OutgoingTransactionImage, OutgoingTransactionImage, NotUsed] =
    completeFlow(pushWithRetryFlow(transaction), ti => ti.transaction.sentImageCount == ti.transaction.totalImageCount)

  /**
    * Push images to a remote, adding retry of failed images (error status code or exception). Files rejected by the
    * remove (status code 400) are ignored with a log message.
    */
  protected def pushWithRetryFlow(transaction: OutgoingTransaction): Flow[OutgoingTransactionImage, OutgoingTransactionImage, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>

      val mergeWithRetry = builder.add(MergePreferred[OutgoingTransactionImage](1, eagerComplete = true))
      val acceptOrRetryBroadcast = builder.add(Broadcast[ResponseImage](2))

      val acceptFilter = (p: ResponseImage) => p match {
        case (Success(response), _) =>
          val status = response.status.intValue
          status >= 200 && status < 300 || status == 400
        case (Failure(_), _) => false
      }

      val acceptPath = {
        val acceptsOnly = Flow[ResponseImage].filter(acceptFilter)
        val handleAccept = Flow[ResponseImage].zipWithIndex.mapAsync(1) {
          case ((Success(response), transactionImage), index) =>
            if (response.status.intValue() == 400)
              SbxLog.warn("Box", s"Image ${transactionImage.image.imageId} rejected on remote, ignoring.")
            handleFileSentForOutgoingTransaction(transactionImage, index + 1 + transaction.sentImageCount)
          case ((_, transactionImage), index) =>
            handleFileSentForOutgoingTransaction(transactionImage, index + 1 + transaction.sentImageCount)
        }
        builder.add(Flow[ResponseImage].via(acceptsOnly).via(handleAccept))
      }

      /**
        * Failed PUSH attempts will travel this path which feeds back to the beginning of the PUSH pipeline for retry.
        * Images are throttled to reduce the rate of failures in cases with repeated failures, such as when the remote
        * is down. Failures are logged in a separate path (failures due to exceptions (typically remote down) are only
        * logged once).
        */
      val retryPath = Flow.fromGraph(GraphDSL.create() { implicit builder =>

        val retriesOnly = builder.add(Flow[ResponseImage].filterNot(acceptFilter))

        val handleRetry = Flow[ResponseImage].statefulMapConcat {
          () =>
            var lastExceptionTimestamp = 0L

          {
            case (Success(response), transactionImage) =>
              (Success(response), transactionImage) :: Nil
            case (Failure(exception), transactionImage) =>
              val now = System.currentTimeMillis
              val duration = now - lastExceptionTimestamp
              lastExceptionTimestamp = now
              if (duration > (retryInterval.toMillis * 1.2).toLong)
                (Failure(exception), transactionImage) :: Nil
              else
                Nil
          }
        }.mapAsync(1) {
          case (Success(response), transactionImage) =>
            Unmarshal(response).to[String].flatMap { errorMessage =>
              SbxLog.warn("Box", s"Failed pushing image ${transactionImage.image.imageId}. Status: ${response.status.intValue()} Message: $errorMessage. Retrying later.")
              val exception = new RuntimeException(errorMessage)
              handleFileSendFailedForOutgoingTransaction(transactionImage, exception)
            }
          case (Failure(exception), transactionImage) =>
            SbxLog.warn("Box", s"Failed pushing image ${transactionImage.image.imageId}. Message: ${exception.getMessage}. Similar error messages suppressed until connection restored.")
            handleFileSendFailedForOutgoingTransaction(transactionImage, exception)
        }

        val throttleFlow = Flow[ResponseImage].throttle(1, retryInterval, 1, ThrottleMode.shaping)

        val retryBroadcast = builder.add(Broadcast[ResponseImage](2))
        val toTransactionImageFlow = builder.add(Flow[ResponseImage].map(_._2))

        retriesOnly ~> retryBroadcast ~> throttleFlow ~> toTransactionImageFlow
                       retryBroadcast ~> handleRetry  ~> Sink.ignore

        FlowShape(retriesOnly.in, toTransactionImageFlow.out)
      })

      mergeWithRetry           ~> pushFlow  ~> acceptOrRetryBroadcast ~> acceptPath
      mergeWithRetry.preferred <~ retryPath <~ acceptOrRetryBroadcast

      FlowShape(mergeWithRetry.in(0), acceptPath.out)
    })

  /**
    * Push a stream of images to a remote
    */
  protected def pushFlow: Flow[OutgoingTransactionImage, ResponseImage, NotUsed] =
    Flow[OutgoingTransactionImage]
      .mapAsync(1) { transactionImage =>
        outgoingTagValuesForImage(transactionImage)
          .map(tagValues => createRequest(box, transactionImage, tagValues))
      }
      .via(pushPool)

  /**
    * Create a PUSH request for a `OutgoingTransactionImage`, possibly with Tag-Value mappings
    */
  protected def createRequest(box: Box, transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): (HttpRequest, OutgoingTransactionImage) = {
    val source = anonymizedDicomData(transactionImage, tagValues)
      .batchWeighted(streamChunkSize, _.length, identity)(_ ++ _)
      .via(Compression.deflate)
    val uri = s"${box.baseUrl}/image?transactionid=${transactionImage.transaction.id}&sequencenumber=${transactionImage.image.sequenceNumber}&totalimagecount=${transactionImage.transaction.totalImageCount}"
    HttpRequest(method = HttpMethods.POST, uri = uri, entity = HttpEntity(ContentTypes.`application/octet-stream`, source)) -> transactionImage
  }

  protected def handleFileSentForOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] =
    updateOutgoingTransaction(transactionImage, sentImageCount)

  protected def handleFileSendFailedForOutgoingTransaction(transactionImage: OutgoingTransactionImage, exception: Throwable): Future[OutgoingTransactionImage] =
    setOutgoingTransactionStatus(transactionImage.transaction, TransactionStatus.WAITING).map(_ => transactionImage)

  protected def handleTransactionFinished(box: Box, transaction: OutgoingTransaction, imageIds: Seq[Long]): Future[Unit] =
    setOutgoingTransactionStatus(transaction, TransactionStatus.FINISHED)
      .flatMap { _ =>
        setRemoteIncomingTransactionStatus(transaction, TransactionStatus.FINISHED)
          .recover { case _: Exception => Unit } // just setting status, ignore if not successful
      }
      .map { _ =>
        SbxLog.info("Box", s"Finished sending ${transaction.totalImageCount} images to box ${box.name}")
        system.eventStream.publish(ImagesSent(Destination(DestinationType.BOX, box.name, box.id), imageIds))
      }

  protected def setRemoteIncomingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit] =
    singleRequest(HttpRequest(method = HttpMethods.PUT, uri = s"${box.baseUrl}/status?transactionid=${transaction.id}", entity = HttpEntity(status.toString)))
      .recover {
        case _: Exception =>
          SbxLog.warn("Box", s"Unable to set remote status of transaction ${transaction.id} to FINISHED.")
      }
      .map(_ => Unit)
}
