/*
 * Copyright 2016 Lars Edenbrandt
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

import se.nimsa.sbx.model.Entity
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.anonymization.AnonymizationProtocol.TagValue
import se.nimsa.sbx.anonymization.AnonymizationProtocol.ImageTagValues
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.app.GeneralProtocol.Destination

object BoxProtocol {

  sealed trait BoxSendMethod {
    override def toString(): String = this match {
      case BoxSendMethod.PUSH => "PUSH"
      case BoxSendMethod.POLL => "POLL"
    }
  }

  object BoxSendMethod {
    case object PUSH extends BoxSendMethod
    case object POLL extends BoxSendMethod

    def withName(string: String) = string match {
      case "PUSH" => PUSH
      case "POLL" => POLL
    }
  }

  sealed trait TransactionStatus {
    override def toString(): String = this match {
      case TransactionStatus.PROCESSING => "PROCESSING"
      case TransactionStatus.WAITING => "WAITING"
      case TransactionStatus.FAILED => "FAILED"
      case TransactionStatus.FINISHED => "FINISHED"
    }
  }

  object TransactionStatus {
    case object PROCESSING extends TransactionStatus
    case object WAITING extends TransactionStatus
    case object FAILED extends TransactionStatus
    case object FINISHED extends TransactionStatus

    def withName(string: String) = string match {
      case "PROCESSING" => PROCESSING
      case "WAITING" => WAITING
      case "FAILED" => FAILED
      case "FINISHED" => FINISHED
    }
  }

  case class RemoteBox(name: String, baseUrl: String)

  case class RemoteBoxConnectionData(name: String)

  case class Box(id: Long, name: String, token: String, baseUrl: String, sendMethod: BoxSendMethod, online: Boolean) extends Entity

  case class OutgoingTransaction(id: Long, boxId: Long, boxName: String, sentImageCount: Long, totalImageCount: Long, created: Long, updated: Long, status: TransactionStatus) extends Entity

  case class OutgoingImage(id: Long, outgoingTransactionId: Long, imageId: Long, sequenceNumber: Long, sent: Boolean) extends Entity
  
  case class OutgoingTagValue(id: Long, outgoingImageId: Long, tagValue: TagValue) extends Entity
  
  case class OutgoingTransactionImage(transaction: OutgoingTransaction, image: OutgoingImage)
  
  case class IncomingTransaction(id: Long, boxId: Long, boxName: String, outgoingTransactionId: Long, receivedImageCount: Long, addedImageCount: Long, totalImageCount: Long, created: Long, updated: Long, status: TransactionStatus) extends Entity

  case class IncomingImage(id: Long, incomingTransactionId: Long, imageId: Long, sequenceNumber: Long, overwrite: Boolean) extends Entity
  
  case class FailedOutgoingTransactionImage(transactionImage: OutgoingTransactionImage, message: String)
  
  // case class PushImageData(transactionId: Long, imageId: Long, totalImageCount: Long, dataset: Attributes)
  
  
  sealed trait BoxRequest

  case class CreateConnection(remoteoxConnectionData: RemoteBoxConnectionData) extends BoxRequest

  case class Connect(remoteBox: RemoteBox) extends BoxRequest

  case class RemoveBox(boxId: Long) extends BoxRequest

  case class GetBoxes(startIndex: Long, count: Long) extends BoxRequest

  case class GetBoxById(boxId: Long) extends BoxRequest
  
  case class GetBoxByToken(token: String) extends BoxRequest

  case class UpdateIncoming(box: Box, transactionId: Long, sequenceNumber: Long, totalImageCount: Long, imageId: Long, overwrite: Boolean) extends BoxRequest

  case class PollOutgoing(box: Box) extends BoxRequest

  case class UpdateOutgoingTransaction(transactionImage: OutgoingTransactionImage) extends BoxRequest
  
  case class SendToRemoteBox(box: Box, imageTagValuesSeq: Seq[ImageTagValues]) extends BoxRequest

  case class GetOutgoingTransactionImage(box: Box, outgoingTransactionId: Long, imageId: Long) extends BoxRequest

  case class GetOutgoingTagValues(transactionImage: OutgoingTransactionImage) extends BoxRequest

  case class MarkOutgoingImageAsSent(box: Box, transactionImage: OutgoingTransactionImage) extends BoxRequest

  case class MarkOutgoingTransactionAsFailed(box: Box, failedTransactionImage: FailedOutgoingTransactionImage) extends BoxRequest
  
  case class GetIncomingTransactions(startIndex: Long, count: Long) extends BoxRequest

  case class GetOutgoingTransactions(startIndex: Long, count: Long) extends BoxRequest
  
  case class GetNextOutgoingTransactionImage(boxId: Long) extends BoxRequest

  case class GetOutgoingImageIdsForTransaction(transaction: OutgoingTransaction) extends BoxRequest
  
  case class SetOutgoingTransactionStatus(transaction: OutgoingTransaction, status: TransactionStatus) extends BoxRequest

  case class SetIncomingTransactionStatus(boxId: Long, transactionImage: OutgoingTransactionImage, status: TransactionStatus) extends BoxRequest

  case class UpdateBoxOnlineStatus(boxId: Long, online: Boolean) extends BoxRequest
  
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

  // box push actor internal messages

  case object PollOutgoing

  case class FileSent(transactionImage: OutgoingTransactionImage)

  case class FileSendFailed(transactionImage: OutgoingTransactionImage, statusCode: Int, e: Exception)

  // box poll actor internal messages

  case object PollRemoteBox

  case object RemoteOutgoingEmpty

  case class RemoteOutgoingTransactionImageFound(transactionImage: OutgoingTransactionImage)

  case class PollRemoteBoxFailed(e: Throwable)

  case class RemoteOutgoingFileFetched(transactionImage: OutgoingTransactionImage, imageId: Long, overwrite: Boolean)

  case class FetchFileFailedTemporarily(transactionImage: OutgoingTransactionImage, e: Throwable)

  case class FetchFileFailedPermanently(transactionImage: OutgoingTransactionImage, e: Throwable)
  
  case object UpdateStatusForBoxesAndTransactions

}
