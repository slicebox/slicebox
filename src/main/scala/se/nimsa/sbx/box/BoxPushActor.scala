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
import akka.actor.{Actor, ActorSelection, ActorSystem, Cancellable, Props, Scheduler}
import akka.event.{Logging, LoggingReceive}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.stream.{KillSwitch, Materializer}
import akka.util.{ByteString, Timeout}
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.streams.DicomStreamOps
import se.nimsa.sbx.storage.StorageService

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class BoxPushActor(storage: StorageService,
                   override val parallelism: Int,
                   boxServicePath: String = "../../BoxService",
                   metaDataServicePath: String = "../../MetaDataService",
                   anonymizationServicePath: String = "../../AnonymizationService")
                  (implicit val materializer: Materializer, timeout: Timeout) extends Actor with DicomStreamOps with BoxPushOps {

  val log = Logging(context.system, this)

  val http = Http(context.system)

  val metaDataService: ActorSelection = context.actorSelection(metaDataServicePath)
  val anonymizationService: ActorSelection = context.actorSelection(anonymizationServicePath)
  val boxService: ActorSelection = context.actorSelection(boxServicePath)

  val transactionKillSwitches = mutable.Map.empty[Long, KillSwitch]

  override implicit val system: ActorSystem = context.system
  override implicit val ec: ExecutionContext = context.dispatcher
  override implicit val scheduler: Scheduler = system.scheduler

  override val streamChunkSize: Long = storage.streamChunkSize

  def receive = LoggingReceive {
    case PushTransaction(box, transaction) =>
      val (_, killSwitch) = pushTransaction(box, transaction)
      transactionKillSwitches(transaction.id) = killSwitch

    case RemoveTransaction(transactionId) =>
      transactionKillSwitches.get(transactionId).foreach(_.shutdown())
  }

  override def callAnonymizationService[R: ClassTag](message: Any): Future[R] =
    anonymizationService.ask(message).mapTo[R]
  override def callMetaDataService[R: ClassTag](message: Any): Future[R] =
    metaDataService.ask(message).mapTo[R]
  override def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable =
    system.scheduler.scheduleOnce(delay)(task)
  override def sliceboxRequest(method: HttpMethod, uri: String, entity: RequestEntity): Future[HttpResponse] =
    http.singleRequest(HttpRequest(method = method, uri = uri, entity = entity))
  override def pendingOutgoingImagesForTransaction(transaction: OutgoingTransaction): Future[Source[OutgoingTransactionImage, NotUsed]] =
    boxService.ask(GetOutgoingImagesForTransaction(transaction)).mapTo[Source[OutgoingTransactionImage, NotUsed]]
  override def outgoingTagValuesForImage(transactionImage: OutgoingTransactionImage): Future[Seq[OutgoingTagValue]] =
    boxService.ask(GetOutgoingTagValues(transactionImage)).mapTo[Seq[OutgoingTagValue]]
  override def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] =
    boxService.ask(UpdateOutgoingTransaction(transactionImage, sentImageCount)).mapTo[OutgoingTransactionImage]
  override def setOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit] =
    boxService.ask(SetOutgoingTransactionStatus(transaction, status)).map(_ => Unit)
  override def anonymizedDicomData(transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Source[ByteString, NotUsed] =
    anonymizedDicomData(transactionImage.image.imageId, tagValues.map(_.tagValue), storage)
}

object BoxPushActor {
  def props(storage: StorageService, parallelism: Int)(implicit materializer: Materializer, timeout: Timeout): Props = Props(new BoxPushActor(storage, parallelism))

}
