package se.nimsa.sbx.box

import akka.NotUsed
import akka.http.scaladsl.model._
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import se.nimsa.sbx.box.BoxProtocol._

import scala.collection.immutable.Seq
import scala.concurrent.Future

trait BoxPushOps extends BoxStreamBase {

  import BoxStreamBase._

  def poll(n: Int): Future[Seq[OutgoingTransactionImage]]
  def outgoingTagValuesForImage(transactionImage: OutgoingTransactionImage): Future[Seq[OutgoingTagValue]]
  def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage]
  def anonymizedDicomData(transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Source[ByteString, NotUsed]

  lazy val pushSink: Sink[Seq[OutgoingTransactionImage], Future[Seq[OutgoingTransactionImage]]] = {
    val pushPool = pool[OutgoingTransactionImage]

    Flow[Seq[OutgoingTransactionImage]]
      .mapConcat(identity)
      .mapAsyncUnordered(parallelism)(createPushRequest)
      .via(pushPool)
      .map(checkResponse).map(_._2)
      .statefulMapConcat(indexInTransaction)
      .mapAsyncUnordered(parallelism)(updateTransaction)
      .toMat(Sink.seq)(Keep.right)
  }

  def pushBatch(): Future[Seq[OutgoingTransactionImage]] = scaladsl.Source.fromFuture(poll(batchSize)).runWith(pushSink)

  def createPushRequest(transactionImage: OutgoingTransactionImage): Future[RequestImage] =
    outgoingTagValuesForImage(transactionImage)
      .map(tagValues => createPushRequest(box, transactionImage, tagValues))

  def createPushRequest(box: Box, transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): (HttpRequest, OutgoingTransactionImage) = {
    val source = anonymizedDicomData(transactionImage, tagValues)
      .batchWeighted(streamChunkSize, _.length, identity)(_ ++ _)
      .via(Compression.deflate)
    val uri = s"${box.baseUrl}/image?transactionid=${transactionImage.transaction.id}&sequencenumber=${transactionImage.image.sequenceNumber}&totalimagecount=${transactionImage.transaction.totalImageCount}"
    HttpRequest(method = HttpMethods.POST, uri = uri, entity = HttpEntity(ContentTypes.`application/octet-stream`, source)) -> transactionImage
  }

  def updateTransaction(imageIndex: (OutgoingTransactionImage, Long)): Future[OutgoingTransactionImage] =
    imageIndex match {
      case (transactionImage, index) => updateOutgoingTransaction(transactionImage, index)
    }

}
