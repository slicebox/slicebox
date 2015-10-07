/*
 * Copyright 2015 Lars Edenbrandt
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
import org.dcm4che3.data.Attributes

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

  case class RemoteBox(name: String, baseUrl: String, secret: String)

  case class RemoteBoxConnectionData(name: String)

  case class Box(id: Long, name: String, token: String, baseUrl: String, sendMethod: BoxSendMethod, online: Boolean) extends Entity

  case class BoxTransferData(id: Long, secret: String) extends Entity
  
  case class OutboxEntry(id: Long, remoteBoxId: Long, remoteBoxName: String, transactionId: Long, sequenceNumber: Long, totalImageCount: Long, imageId: Long, failed: Boolean) extends Entity

  case class FailedOutboxEntry(outboxEntry: OutboxEntry, message: String)
  
  case class SentEntry(id: Long, remoteBoxId: Long, remoteBoxName: String, transactionId: Long, sentImageCount: Long, totalImageCount: Long, lastUpdated: Long) extends Entity

  case class SentImage(id: Long, sentEntryId: Long, imageId: Long) extends Entity

  case class InboxEntry(id: Long, remoteBoxId: Long, remoteBoxName: String, transactionId: Long, receivedImageCount: Long, totalImageCount: Long, lastUpdated: Long) extends Entity

  case class InboxImage(id: Long, inboxEntryId: Long, imageId: Long) extends Entity
  
  case class ImageTagValues(imageId: Long, tagValues: Seq[TagValue])
  
  case class PushImageData(transactionId: Long, sequenceNumber: Long, totalImageCount: Long, dataset: Attributes)

  case class TransactionTagValue(id: Long, transactionId: Long, imageId: Long, tagValue: TagValue) extends Entity
  
  
  sealed trait BoxRequest

  case class CreateConnection(remoteBoxConnectionData: RemoteBoxConnectionData) extends BoxRequest

  case class Connect(remoteBox: RemoteBox) extends BoxRequest

  case class RemoveBox(boxId: Long) extends BoxRequest

  case object GetBoxes extends BoxRequest

  case class GetBoxById(boxId: Long) extends BoxRequest
  
  case class GetBoxTransferDataByBoxId(boxId: Long) extends BoxRequest
  
  case class GetBoxByToken(token: String) extends BoxRequest

  case class UpdateInbox(token: String, transactionId: Long, sequenceNumber: Long, totalImageCount: Long, imageId: Long) extends BoxRequest

  case class PollOutbox(token: String) extends BoxRequest

  case class SendToRemoteBox(remoteBoxId: Long, imageTagValuesSeq: Seq[ImageTagValues]) extends BoxRequest

  case class GetOutboxEntry(token: String, transactionId: Long, sequenceNumber: Long) extends BoxRequest

  case class GetTransactionTagValues(imageId: Long, transactionId: Long) extends BoxRequest

  case class DeleteOutboxEntry(token: String, transactionId: Long, sequenceNumber: Long) extends BoxRequest

  case class MarkOutboxTransactionAsFailed(token: String, transactionId: Long, message: String) extends BoxRequest
  
  case object GetInbox extends BoxRequest

  case object GetOutbox extends BoxRequest

  case object GetSent extends BoxRequest
  
  case class RemoveInboxEntry(inboxEntryId: Long) extends BoxRequest
  
  case class RemoveOutboxEntry(outboxEntryId: Long) extends BoxRequest

  case class RemoveSentEntry(sentEntryId: Long) extends BoxRequest
  
  case class GetImagesForInboxEntry(inboxEntryId: Long) extends BoxRequest
  
  case class GetImagesForSentEntry(sentEntryId: Long) extends BoxRequest
  
  case class GetInboxEntryForImageId(imageId: Long) extends BoxRequest
  
  
  case class InboxEntryRemoved(inboxEntryId: Long)

  case class OutboxEntryRemoved(outboxEntryId: Long)

  case class SentEntryRemoved(sentEntryId: Long)

  case class RemoteBoxAdded(box: Box, boxTransferData: BoxTransferData)
  
  case class BoxRemoved(boxId: Long)

  case class Boxes(boxes: Seq[Box])

  case class InboxUpdated(token: String, transactionId: Long, sequenceNumber: Long, totalImageCount: Long)

  case object OutboxEmpty

  case class ImagesAddedToOutbox(remoteBoxId: Long, imageIds: Seq[Long])

  case object OutboxEntryNotFound

  case object OutboxEntryDeleted

  case object OutboxTransactionMarkedAsFailed
  
  case class Inbox(entries: Seq[InboxEntry])

  case class Outbox(entries: Seq[OutboxEntry])

  case class Sent(entries: Seq[SentEntry])
  
  case object BoxNotFound

  // box push actor internal messages

  case object PollOutbox

  case class FileSent(outboxEntry: OutboxEntry)

  case class FileSendFailed(outboxEntry: OutboxEntry, statusCode: Int, e: Exception)

  // box poll actor internal messages

  case object PollRemoteBox

  case object RemoteOutboxEmpty

  case class RemoteOutboxEntryFound(remoteOutboxEntry: OutboxEntry)

  case class PollRemoteBoxFailed(e: Throwable)

  case class RemoteOutboxFileFetched(remoteOutboxEntry: OutboxEntry, imageId: Long)

  case class FetchFileFailed(remoteOutboxEntry: OutboxEntry, e: Throwable)

  case class HandlingFetchedFileFailed(remoteOutboxEntry: OutboxEntry, e: Throwable)
}
