package se.nimsa.sbx.dicom.streams

import akka.util.ByteString
import org.dcm4che3.data.SpecificCharacterSet
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey


case class AnonymizationKeyPart(anonymizationKey: Option[AnonymizationKey]) extends DicomPart {
  def bytes = ByteString.empty
  def bigEndian = false
}




