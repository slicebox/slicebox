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
import akka.actor.{Actor, ActorSelection, ActorSystem, Cancellable, Props}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.ask
import akka.stream.scaladsl.{Compression, Source}
import akka.stream.{KillSwitch, Materializer}
import akka.util.{ByteString, Timeout}
import se.nimsa.sbx.app.GeneralProtocol.{Destination, DestinationType, ImagesSent}
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.streams.DicomStreamOps
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.storage.StorageService

import scala.collection.immutable.Seq
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class BoxPushActor(override val box: Box,
                   val storage: StorageService,
                   override val retryInterval: FiniteDuration = 15.seconds,
                   boxServicePath: String = "../../BoxService",
                   metaDataServicePath: String = "../../MetaDataService",
                   anonymizationServicePath: String = "../../AnonymizationService")
                  (implicit val materializer: Materializer, timeout: Timeout) extends Actor with DicomStreamOps with BoxPushOps {

  val log = Logging(context.system, this)

  val metaDataService: ActorSelection = context.actorSelection(metaDataServicePath)
  val anonymizationService: ActorSelection = context.actorSelection(anonymizationServicePath)
  val boxService: ActorSelection = context.actorSelection(boxServicePath)

  override implicit val system: ActorSystem = context.system
  override implicit val ec: ExecutionContext = context.dispatcher

  val switch: KillSwitch = pollAndTransfer(() => pushBatch()).run()

  override def postStop(): Unit =
    switch.shutdown()

  def receive = LoggingReceive {
    case _ =>
  }

  override def callAnonymizationService[R: ClassTag](message: Any): Future[R] =
    anonymizationService.ask(message).mapTo[R]
  override def callMetaDataService[R: ClassTag](message: Any): Future[R] =
    metaDataService.ask(message).mapTo[R]
  override def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable =
    system.scheduler.scheduleOnce(delay)(task)

  override def poll(n: Int): Future[Seq[OutgoingTransactionImage]] =
    boxService.ask(PollOutgoing(box, n)).mapTo[Seq[OutgoingTransactionImage]]
  override def outgoingTagValuesForImage(transactionImage: OutgoingTransactionImage): Future[Seq[OutgoingTagValue]] =
    boxService.ask(GetOutgoingTagValues(transactionImage)).mapTo[Seq[OutgoingTagValue]]
  override def anonymizedDicomData(transactionImage: OutgoingTransactionImage, tagValues: Seq[OutgoingTagValue]): Source[ByteString, NotUsed] =
    anonymizedDicomData(transactionImage.image.imageId, tagValues.map(_.tagValue), storage)
      .batchWeighted(storage.streamChunkSize, _.length, identity)(_ ++ _)
      .via(Compression.deflate)
  override def updateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long): Future[OutgoingTransactionImage] =
    boxService.ask(UpdateOutgoingTransaction(transactionImage, sentImageCount)).mapTo[OutgoingTransactionImage]
      .flatMap { updatedTransactionImage =>
        if (updatedTransactionImage.transaction.sentImageCount >= updatedTransactionImage.transaction.totalImageCount)
          getImageIdsForOutgoingTransaction(transactionImage.transaction).flatMap { imageIds =>
            finalizeOutgoingTransaction(updatedTransactionImage.transaction, imageIds)
          }.map(_ => updatedTransactionImage)
        else
          Future.successful(updatedTransactionImage)
      }

  def finalizeOutgoingTransaction(transaction: OutgoingTransaction, imageIds: Seq[Long]): Future[Unit] =
    setOutgoingTransactionStatus(transaction, TransactionStatus.FINISHED)
      .map { _ =>
        SbxLog.info("Box", s"Finished sending ${transaction.totalImageCount} images to box ${box.name}")
        system.eventStream.publish(ImagesSent(Destination(DestinationType.BOX, box.name, box.id), imageIds))
      }

  def setOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus): Future[Unit] =
    boxService.ask(SetOutgoingTransactionStatus(transaction, status)).map(_ => Unit)

  def getImageIdsForOutgoingTransaction(transaction: OutgoingTransaction): Future[Seq[Long]] =
    boxService.ask(GetImageIdsForOutgoingTransaction(transaction.id)).mapTo[Seq[Long]]
}

object BoxPushActor {
  def props(box: Box, storage: StorageService)(implicit materializer: Materializer, timeout: Timeout): Props = Props(new BoxPushActor(box, storage))
}
