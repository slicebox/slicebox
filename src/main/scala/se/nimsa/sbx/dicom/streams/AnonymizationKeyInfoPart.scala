package se.nimsa.sbx.dicom.streams

import akka.util.ByteString
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey


case class AnonymizationKeyInfoPart(existingKeys: Seq[AnonymizationKey], newKey: AnonymizationKey) extends DicomPart {
  def bytes = ByteString.empty
  def bigEndian = false
}




