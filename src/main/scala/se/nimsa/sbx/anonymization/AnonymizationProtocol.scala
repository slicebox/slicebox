package se.nimsa.sbx.anonymization

import se.nimsa.sbx.model.Entity
import org.dcm4che3.data.Attributes

object AnonymizationProtocol {
  
  case class TransactionTagValue(id: Long, imageFileId: Long, transactionId: Long, tag: Int, value: String) extends Entity

  case class AnonymizationKey(
    id: Long,
    created: Long,
    remoteBoxId: Long,
    transactionId: Long,
    remoteBoxName: String,
    patientName: String,
    anonPatientName: String,
    patientID: String,
    anonPatientID: String,
    patientBirthDate: String,
    studyInstanceUID: String,
    anonStudyInstanceUID: String,
    studyDescription: String,
    studyID: String,
    accessionNumber: String,
    seriesInstanceUID: String,
    anonSeriesInstanceUID: String,
    frameOfReferenceUID: String,
    anonFrameOfReferenceUID: String) extends Entity

  trait AnonymizationRequest
    
  case class ReverseAnonymization(dataset: Attributes) extends AnonymizationRequest

  case class HarmonizeAnonymization(outboxEntry: OutboxEntry, dataset: Attributes, anonDataset: Attributes) extends AnonymizationRequest

  case class GetAnonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]) extends AnonymizationRequest

  case class RemoveAnonymizationKey(anonymizationKeyId: Long) extends AnonymizationRequest

  case class AnonymizationKeyRemoved(anonymizationKeyId: Long)

  case class AnonymizationKeys(anonymizationKeys: Seq[AnonymizationKey])

}