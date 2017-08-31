package se.nimsa.sbx.dicom.streams

import akka.util.ByteString
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey


case class AnonymizationKeysPart(patientKeys: Seq[AnonymizationKey],
                                 patientKey: Option[AnonymizationKey],
                                 studyKey: Option[AnonymizationKey],
                                 seriesKey: Option[AnonymizationKey]) extends DicomPart {
  def bytes = ByteString.empty
  def bigEndian = false
}




