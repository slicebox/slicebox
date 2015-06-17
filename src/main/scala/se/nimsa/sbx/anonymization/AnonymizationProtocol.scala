package se.nimsa.sbx.anonymization

import se.nimsa.sbx.model.Entity
import org.dcm4che3.data.Attributes

object AnonymizationProtocol {

  case class TagValue(tag: Int, value: String)

  case class AnonymizationKey(
    id: Long,
    created: Long,
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

  case class Anonymize(dataset: Attributes, tagValues: Seq[TagValue]) extends AnonymizationRequest

  case class GetAnonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]) extends AnonymizationRequest

  case class RemoveAnonymizationKey(anonymizationKeyId: Long) extends AnonymizationRequest

  case class AnonymizationKeyRemoved(anonymizationKeyId: Long)

  case class AnonymizationKeys(anonymizationKeys: Seq[AnonymizationKey])

}