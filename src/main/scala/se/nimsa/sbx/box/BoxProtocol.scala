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

import se.nimsa.sbx.anonymization.AnonymizationProtocol.{ImageTagValues, TagValue}
import se.nimsa.sbx.model.Entity

object BoxProtocol {

  sealed trait BoxSendMethod {
    override def toString: String = this match {
      case BoxSendMethod.PUSH => "PUSH"
      case BoxSendMethod.POLL => "POLL"
      case BoxSendMethod.UNKNOWN => "UNKNOWN"
    }
  }

  object BoxSendMethod {

    case object PUSH extends BoxSendMethod

    case object POLL extends BoxSendMethod

    case object UNKNOWN extends BoxSendMethod

    def withName(string: String): BoxSendMethod = string match {
      case "PUSH" => PUSH
      case "POLL" => POLL
      case _ => UNKNOWN
    }
  }

  sealed trait TransactionStatus {
    override def toString: String = this match {
      case TransactionStatus.PROCESSING => "PROCESSING"
      case TransactionStatus.WAITING => "WAITING"
      case TransactionStatus.FAILED => "FAILED"
      case TransactionStatus.FINISHED => "FINISHED"
      case TransactionStatus.UNKNOWN => "UNKNOWN"
    }
  }

  object TransactionStatus {

    case object PROCESSING extends TransactionStatus

    case object WAITING extends TransactionStatus

    case object FAILED extends TransactionStatus

    case object FINISHED extends TransactionStatus

    case object UNKNOWN extends TransactionStatus

    def withName(string: String): TransactionStatus = string match {
      case "PROCESSING" => PROCESSING
      case "WAITING" => WAITING
      case "FAILED" => FAILED
      case "FINISHED" => FINISHED
      case _ => UNKNOWN
    }
  }

  object EmptyTransactionException extends RuntimeException()

  object RemoteBoxUnavailableException extends RuntimeException()

  class RemoteTransactionFailedException() extends RuntimeException("Remote transaction reported failure")

  case class RemoteBox(name: String, baseUrl: String)

  case class RemoteBoxConnectionData(name: String)

  case class Box(id: Long, name: String, token: String, baseUrl: String, sendMethod: BoxSendMethod, online: Boolean) extends Entity

  case class OutgoingTransaction(id: Long, boxId: Long, boxName: String, sentImageCount: Long, totalImageCount: Long, created: Long, updated: Long, status: TransactionStatus) extends Entity

  case class OutgoingImage(id: Long, outgoingTransactionId: Long, imageId: Long, sequenceNumber: Long, sent: Boolean) extends Entity

  case class OutgoingTagValue(id: Long, outgoingImageId: Long, tagValue: TagValue) extends Entity

  case class OutgoingTransactionImage(transaction: OutgoingTransaction, image: OutgoingImage) {
    def update(sentImageCount: Long): OutgoingTransactionImage = {
      val updatedImage = image.copy(sent = true)
      val updatedTransaction = transaction.copy(
        sentImageCount = sentImageCount,
        updated = System.currentTimeMillis,
        status = TransactionStatus.PROCESSING)
      OutgoingTransactionImage(updatedTransaction, updatedImage)
    }
  }

  case class IncomingTransaction(id: Long, boxId: Long, boxName: String, outgoingTransactionId: Long, receivedImageCount: Long, addedImageCount: Long, totalImageCount: Long, created: Long, updated: Long, status: TransactionStatus) extends Entity

  case class IncomingImage(id: Long, incomingTransactionId: Long, imageId: Long, sequenceNumber: Long, overwrite: Boolean) extends Entity

  case class FailedOutgoingTransactionImage(transactionImage: OutgoingTransactionImage, message: String)


  sealed trait BoxRequest

  case class CreateConnection(remoteoxConnectionData: RemoteBoxConnectionData) extends BoxRequest

  case class Connect(remoteBox: RemoteBox) extends BoxRequest

  case class RemoveBox(boxId: Long) extends BoxRequest

  case class GetBoxes(startIndex: Long, count: Long) extends BoxRequest

  case class GetBoxById(boxId: Long) extends BoxRequest

  case class GetBoxByToken(token: String) extends BoxRequest

  case class UpdateIncoming(box: Box, transactionId: Long, sequenceNumber: Long, totalImageCount: Long, imageId: Long, overwrite: Boolean) extends BoxRequest

  case class PollOutgoing(box: Box) extends BoxRequest

  case class UpdateOutgoingTransaction(transactionImage: OutgoingTransactionImage, sentImageCount: Long) extends BoxRequest

  case class SendToRemoteBox(box: Box, imageTagValuesSeq: Seq[ImageTagValues]) extends BoxRequest

  case class GetOutgoingTransactionImage(box: Box, outgoingTransactionId: Long, imageId: Long) extends BoxRequest

  case class GetOutgoingTagValues(transactionImage: OutgoingTransactionImage) extends BoxRequest

  case class MarkOutgoingImageAsSent(box: Box, transactionImage: OutgoingTransactionImage) extends BoxRequest

  case class MarkOutgoingTransactionAsFailed(box: Box, failedTransactionImage: FailedOutgoingTransactionImage) extends BoxRequest

  case class GetIncomingTransactions(startIndex: Long, count: Long) extends BoxRequest

  case class GetOutgoingTransactions(startIndex: Long, count: Long) extends BoxRequest

  case class GetNextOutgoingTransactionImage(boxId: Long) extends BoxRequest

  case class GetOutgoingImagesForTransaction(transaction: OutgoingTransaction) extends BoxRequest

  case class SetOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus) extends BoxRequest

  case object OutgoingTransactionStatusUpdated

  case object IncomingTransactionStatusUpdated

  case class UpdateBoxOnlineStatus(boxId: Long, online: Boolean) extends BoxRequest

  case class GetIncomingTransactionStatus(box: Box, transactionId: Long) extends BoxRequest

  case class SetIncomingTransactionStatus(boxId: Long, transactionId: Long, status: TransactionStatus) extends BoxRequest

  case class RemoveIncomingTransaction(incomingTransactionId: Long) extends BoxRequest

  case class RemoveOutgoingTransaction(outgoingTransactionId: Long) extends BoxRequest

  case class GetImageIdsForIncomingTransaction(incomingTransactionId: Long) extends BoxRequest

  case class GetImageIdsForOutgoingTransaction(outgoingTransactionId: Long) extends BoxRequest


  case class IncomingTransactionRemoved(incomingTransactionId: Long)

  case class OutgoingTransactionRemoved(outgoingTransactionId: Long)

  case class RemoteBoxAdded(box: Box)

  case class BoxRemoved(boxId: Long)

  case class Boxes(boxes: Seq[Box])

  case class IncomingUpdated(transaction: IncomingTransaction)

  case class ImagesAddedToOutgoing(boxId: Long, imageIds: Seq[Long])

  case object OutgoingImageMarkedAsSent

  case object OutgoingTransactionMarkedAsFailed

  case class IncomingTransactions(transactions: Seq[IncomingTransaction])

  case class OutgoingTransactions(transactions: Seq[OutgoingTransaction])

  // box service actor internal messages

  case object UpdateStatusForBoxesAndTransactions

  // box push actor internal messages

  case object PollOutgoing

  case object TransferFinished

  // box poll actor internal messages

  case object PollIncoming

  case class PushTransaction(box: Box, transaction: OutgoingTransaction)

  case class RemoveTransaction(outgoingTransactionId: Long)
}
