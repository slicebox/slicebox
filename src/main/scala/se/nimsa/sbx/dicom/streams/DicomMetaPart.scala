package se.nimsa.sbx.dicom.streams

import akka.util.ByteString
import org.dcm4che3.data.SpecificCharacterSet
import se.nimsa.dcm4che.streams.DicomParts._


  case class DicomMetaPart(transferSyntaxUid: Option[String],
                           specificCharacterSet: Option[SpecificCharacterSet],
                           patientId: Option[String],
                           patientName: Option[String],
                           identityRemoved: Option[String],
                           studyInstanceUID: Option[String] = None,
                           seriesInstanceUID: Option[String] = None) extends DicomPart {
    def bytes = ByteString.empty
    def bigEndian = false
    def isAnonymized = identityRemoved.exists(_.toUpperCase == "YES")
  }




