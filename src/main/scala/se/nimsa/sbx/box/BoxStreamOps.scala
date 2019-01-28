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

import akka.actor.ActorSystem
import akka.http.scaladsl.{Http, HttpExt}
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, MergePreferred, RunnableGraph, Sink}
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.log.SbxLog

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

trait BoxStreamOps extends BoxJsonFormats with PlayJsonSupport {

  import BoxStreamOps._

  val box: Box
  val transferType: String
  val retryInterval: FiniteDuration
  val batchSize: Int
  val parallelism: Int

  implicit val system: ActorSystem
  implicit val materializer: Materializer
  implicit val ec: ExecutionContext

  lazy val http: HttpExt = Http(system)

  def pollAndTransfer[Mat](transferBatch: () => Future[Seq[OutgoingTransactionImage]]): RunnableGraph[KillSwitch] = {
    RunnableGraph.fromGraph(GraphDSL.create(KillSwitches.single[Tick]) {
      implicit b =>
        switch =>

          import GraphDSL.Implicits._

          val ticker = scaladsl.Source.tick(retryInterval, retryInterval, tick)
            .detach

          val merge = b.add(MergePreferred[Tick](1))
          val bcast = b.add(Broadcast[Try[Seq[OutgoingTransactionImage]]](2))

          val tryBatch = (_: Tick) => transferBatch().map(Success.apply).recover { case t: Throwable => Failure(t) }
          val batchFlow = Flow[Tick].mapAsync(1)(tryBatch)

          val onlineAndTransferring = Flow[Try[Seq[OutgoingTransactionImage]]]
            .collect {
              case Success(images) if images.nonEmpty => tick
            }

          val failedBatchPath = Flow[Try[Seq[OutgoingTransactionImage]]]
            .collect {
              case f: Failure[_] => f
            }
            .statefulMapConcat {
              () =>
                var lastExceptionTimestamp = 0L

              {
                case Failure(exception) =>
                  val now = System.currentTimeMillis
                  val duration = now - lastExceptionTimestamp
                  lastExceptionTimestamp = now
                  if (duration > (retryInterval.toMillis * 1.2).toLong)
                    Failure(exception) :: Nil
                  else
                    Nil
              }
            }
            .map { failure =>
              failure.exception match {
                case e: TransactionException =>
                  SbxLog.warn("Box", s"Connection to ${box.name} ($transferType) failed for image ${e.transactionImage.image.imageId}: ${e.getMessage}, cause: ${if (e.getCause != null) e.getCause.getMessage else "none"}. Retrying later.")
                case t: Throwable =>
                  SbxLog.warn("Box", s"Connection to ${box.name} ($transferType) failed: ${t.getMessage}. Retrying later.")
              }
              failure
            }

          ticker ~> merge.in(0)
          merge ~> switch ~> batchFlow ~> bcast
          merge.preferred <~ onlineAndTransferring <~ bcast
          bcast ~> failedBatchPath ~> Sink.ignore

          ClosedShape
    })
  }

  def pool[T]: Flow[(HttpRequest, T), (Try[HttpResponse], T), _] =
    box.baseUrl match {
      case pattern(protocolPart, host, portPart) =>
        val protocol = Option(protocolPart).getOrElse("http")
        val port = Option(portPart) match {
          case None if protocol == "https" => 443
          case None => 80
          case Some(portString) => portString.toInt
        }
        if (port == 443)
          http.cachedHostConnectionPoolHttps[T](host, port)
        else
          http.cachedHostConnectionPool[T](host, port)
    }

  def checkResponse(responseImage: ResponseImage): (HttpResponse, OutgoingTransactionImage) = {
    responseImage match {
      case (Success(response), transactionImage) =>
        response.status.intValue match {
          case status if status >= 200 && status < 300 => (response, transactionImage)
          case status if status == 400 =>
            response.discardEntityBytes()
            SbxLog.warn("Box", s"${box.name} ($transferType): Ignoring rejected image ${transactionImage.image.imageId}")
            (response.copy(entity = HttpEntity.Empty), transactionImage)
          case status if status == 404 =>
            response.discardEntityBytes()
            SbxLog.warn("Box", s"${box.name} ($transferType): Ignoring removed image ${transactionImage.image.imageId}")
            (response.copy(entity = HttpEntity.Empty), transactionImage)
          case _ =>
            response.discardEntityBytes()
            throw new TransactionException(transactionImage, s"Connection to ${box.name} ($transferType) failed for image ${transactionImage.image.imageId}", null)
        }
      case (Failure(exception), transactionImage) => throw new TransactionException(transactionImage, s"Connection to ${box.name} ($transferType) failed for image ${transactionImage.image.id}", exception)
    }
  }

  def setRemoteOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit] =
    Marshal(BoxTransactionStatus(status))
      .to[RequestEntity]
      .flatMap(entity => singleRequest(HttpRequest(method = HttpMethods.PUT, uri = s"${box.baseUrl}/status?transactionid=${transaction.id}", entity = entity)))
      .recover { case _: Exception => SbxLog.warn("Box", s"Unable to set remote status of transaction ${transaction.id} to $status.") }
      .map(_ => Unit)

  def singleRequest(request: HttpRequest): Future[HttpResponse] = http.singleRequest(request)
}

object BoxStreamOps {

  type RequestImage = (HttpRequest, OutgoingTransactionImage)
  type ResponseImage = (Try[HttpResponse], OutgoingTransactionImage)

  class TransactionException(val transactionImage: OutgoingTransactionImage, message: String, cause: Throwable) extends Exception(message, cause)

  case class Tick()

  val tick = Tick()

  val pattern: Regex = """(?:([A-Za-z]*)://)?([^\:|/]+)?:?([0-9]+)?.*""".r

  val indexInTransaction: () => OutgoingTransactionImage => List[(OutgoingTransactionImage, Long)] = () => {
    val indices = mutable.Map.empty[Long, Long]

    transactionImage => {
      val key = transactionImage.transaction.id
      val index = indices.getOrElse(key, transactionImage.transaction.sentImageCount) + 1
      indices(key) = index
      (transactionImage, index) :: Nil
    }
  }
}