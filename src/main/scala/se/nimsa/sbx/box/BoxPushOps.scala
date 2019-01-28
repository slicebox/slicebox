/*
 * Copyright 2014 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.box

import akka.NotUsed
import akka.http.scaladsl.model._
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import se.nimsa.sbx.box.BoxProtocol._

import scala.collection.immutable.Seq
import scala.concurrent.Future

trait BoxPushOps extends BoxStreamOps {

  import BoxStreamOps._

  override val transferType: String = "push"

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
      .mapAsync(1)(updateOutgoingTransaction)
      .toMat(Sink.seq)(Keep.right)
  }

  def pushBatch(): Future[Seq[OutgoingTransactionImage]] = scaladsl.Source.fromFuture(poll(batchSize)).runWith(pushSink)

  def createPushRequest(transactionImage: OutgoingTransactionImage): Future[RequestImage] =
    outgoingTagValuesForImage(transactionImage)
      .map(tagValues => createPushRequest(box, transactionImage, tagValues))

  def createPushRequest(box: Box, transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): (HttpRequest, OutgoingTransactionImage) = {
    val source = anonymizedDicomData(transactionImage, tagValues)
    val uri = s"${box.baseUrl}/image?transactionid=${transactionImage.transaction.id}&sequencenumber=${transactionImage.image.sequenceNumber}&totalimagecount=${transactionImage.transaction.totalImageCount}"
    HttpRequest(method = HttpMethods.POST, uri = uri, entity = HttpEntity(ContentTypes.`application/octet-stream`, source)) -> transactionImage
  }

  def updateOutgoingTransaction(imageIndex: (OutgoingTransactionImage, Long)): Future[OutgoingTransactionImage] =
    imageIndex match {
      case (transactionImage, index) => updateOutgoingTransaction(transactionImage, index)
    }

}
