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
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{KillSwitch, Materializer}
import akka.util.{ByteString, Timeout}
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.streams.DicomStreamOps
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.storage.StorageService

import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try
import scala.util.matching.Regex

class BoxPushActor(override val box: Box,
                   storage: StorageService,
                   pollInterval: FiniteDuration = 15.seconds,
                   boxServicePath: String = "../../BoxService",
                   metaDataServicePath: String = "../../MetaDataService",
                   anonymizationServicePath: String = "../../AnonymizationService")
                  (implicit val materializer: Materializer, timeout: Timeout) extends Actor with DicomStreamOps with BoxPushOps {
  import BoxPushActor._

  val log = Logging(context.system, this)

  val metaDataService: ActorSelection = context.actorSelection(metaDataServicePath)
  val anonymizationService: ActorSelection = context.actorSelection(anonymizationServicePath)
  val boxService: ActorSelection = context.actorSelection(boxServicePath)

  val transactionKillSwitches = mutable.Map.empty[Long, KillSwitch]

  override implicit val system: ActorSystem = context.system
  override implicit val ec: ExecutionContext = context.dispatcher
  override implicit val scheduler: Scheduler = system.scheduler

  override protected val streamChunkSize: Long = storage.streamChunkSize

  val http = Http(system)

  override protected val pool: Flow[(HttpRequest, OutgoingTransactionImage), (Try[HttpResponse], OutgoingTransactionImage), Http.HostConnectionPool] =
    box.baseUrl match {
      case pattern(protocolPart, host, portPart) =>
        val protocol = Option(protocolPart).getOrElse("http")
        val port = Option(portPart) match {
          case None if protocol == "https" => 443
          case None => 80
          case Some(portString) => portString.toInt
        }
        if (port == 443)
          http.cachedHostConnectionPoolHttps[OutgoingTransactionImage](host, port)
        else
          http.cachedHostConnectionPool[OutgoingTransactionImage](host, port)
    }

  val poller: Cancellable = system.scheduler.schedule(pollInterval, pollInterval) {
    self ! PollOutgoing
  }

  override def postStop(): Unit =
    transactionKillSwitches.values.foreach(_.shutdown())
    poller.cancel()

  def receive = LoggingReceive {
    case PollOutgoing =>
      boxService.ask(GetOutgoingTransactionsForBox(box))
        .mapTo[Seq[OutgoingTransaction]]
        .map(outgoingTransactions =>
          outgoingTransactions
            .filter(transaction => !isPushingTransaction(transaction))
            .foreach(transaction => self ! PushTransaction(transaction)))

    case PushTransaction(transaction) =>
      if (!transactionKillSwitches.contains(transaction.id)) {
        val (_, killSwitch) = pushTransaction(transaction)
        transactionKillSwitches(transaction.id) = killSwitch
      }

    case RemoveTransaction(transactionId) =>
      transactionKillSwitches.get(transactionId).foreach(_.shutdown())
      transactionKillSwitches.remove(transactionId)
  }

  protected def isPushingTransaction(transaction: OutgoingTransaction): Boolean =
    transactionKillSwitches.contains(transaction.id)

  override def callAnonymizationService[R: ClassTag](message: Any): Future[R] =
    anonymizationService.ask(message).mapTo[R]
  override def callMetaDataService[R: ClassTag](message: Any): Future[R] =
    metaDataService.ask(message).mapTo[R]
  override def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable =
    system.scheduler.scheduleOnce(delay)(task)
  override protected def pendingOutgoingImagesForTransaction(transaction: OutgoingTransaction): Future[Source[OutgoingTransactionImage, NotUsed]] =
    boxService.ask(GetOutgoingImagesForTransaction(transaction)).mapTo[Source[OutgoingTransactionImage, NotUsed]]
  override protected def outgoingTagValuesForImage(transactionImage: OutgoingTransactionImage): Future[Seq[OutgoingTagValue]] =
    boxService.ask(GetOutgoingTagValues(transactionImage)).mapTo[Seq[OutgoingTagValue]]
  override protected def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] =
    boxService.ask(UpdateOutgoingTransaction(transactionImage, sentImageCount)).mapTo[OutgoingTransactionImage]
  override protected def setOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit] =
    boxService.ask(SetOutgoingTransactionStatus(transaction, status)).map(_ => Unit)
  override protected def setRemoteIncomingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit] =
    http.singleRequest(HttpRequest(
      method = HttpMethods.PUT,
      uri = s"${box.baseUrl}/status?transactionid=${transaction.id}",
      entity = HttpEntity(status.toString)))
      .recover {
        case _: Exception =>
          SbxLog.warn("Box", s"Unable to set remote status of transaction ${transaction.id} to FINISHED.")
      }
      .map(_ => Unit)
  override protected def anonymizedDicomData(transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Source[ByteString, NotUsed] =
    anonymizedDicomData(transactionImage.image.imageId, tagValues.map(_.tagValue), storage)
}

object BoxPushActor {
  def props(box: Box, storage: StorageService)(implicit materializer: Materializer, timeout: Timeout): Props = Props(new BoxPushActor(box, storage))
  val pattern: Regex = """(?:([A-Za-z]*)://)?([^\:|/]+)?:?([0-9]+)?.*""".r
}
