package se.vgregion.box

import se.vgregion.model.Entity
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

  
  case class RemoteBox(name: String, baseUrl: String)

  case class RemoteBoxName(value: String)

  case class BoxBaseUrl(value: String)
  
  case class ImageId(value: Long)
  
  case class ImageIds(imageIds: Seq[Long])

  case class Box(id: Long, name: String, token: String, baseUrl: String, sendMethod: BoxSendMethod, online: Boolean) extends Entity

  case class OutboxEntry(id: Long, remoteBoxId: Long, transactionId: Long, sequenceNumber: Long, totalImageCount: Long, imageId: Long, failed: Boolean) extends Entity

  case class OutboxEntryInfo(id: Long, remoteBoxName: String, transactionId: Long, sequenceNumber: Long, totalImageCount: Long, imageId: Long, failed: Boolean)
  
  case class InboxEntry(id: Long, remoteBoxId: Long, transactionId: Long, receivedImageCount: Long, totalImageCount: Long) extends Entity
  
  case class InboxEntryInfo(remoteBoxName: String, transactionId: Long, receivedImageCount: Long, totalImageCount: Long)
  
  case class PushImageData(transactionId: Long, sequenceNumber: Long, totalImageCount: Long, dataset: Attributes)
  
  sealed trait BoxRequest

  case class GenerateBoxBaseUrl(remoteBoxName: String) extends BoxRequest

  case class AddRemoteBox(remoteBox: RemoteBox) extends BoxRequest

  case class RemoveBox(boxId: Long) extends BoxRequest

  case object GetBoxes extends BoxRequest

  case class ValidateToken(token: String) extends BoxRequest
  
  case class UpdateInbox(token: String, transactionId: Long, sequenceNumber: Long, totalImageCount: Long) extends BoxRequest
  
  case class PollOutbox(token: String) extends BoxRequest
  
  case class SendImagesToRemoteBox(remoteBoxId: Long, imageIds: Seq[Long]) extends BoxRequest
  
  case class GetOutboxEntry(token: String, transactionId: Long, sequenceNumber: Long) extends BoxRequest
  
  case class DeleteOutboxEntry(token: String, transactionId: Long, sequenceNumber: Long) extends BoxRequest
  
  case object GetInbox extends BoxRequest
  
  case object GetOutbox extends BoxRequest
  
  case class RemoveOutboxEntry(outboxEntryId: Long) extends BoxRequest
  
  case class OutboxEntryRemoved(outboxEntryId: Long)

  case class RemoteBoxAdded(box: Box)

  case class BoxRemoved(boxId: Long)

  case class Boxes(boxes: Seq[Box])

  case class BoxBaseUrlGenerated(baseUrl: String)

  case class ValidToken(token: String)

  case class InvalidToken(token: String)
  
  case class InboxUpdated(token: String, transactionId: Long, sequenceNumber: Long, totalImageCount: Long)
  
  case object OutboxEmpty
  
  case class ImagesSent(remoteBoxId: Long, imageIds: Seq[Long])
  
  case object OutboxEntryNotFound
  
  case object OutboxEntryDeleted
  
  case class Inbox(entries: Seq[InboxEntryInfo])
  
  case class Outbox(entries: Seq[OutboxEntryInfo])

}