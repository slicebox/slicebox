package se.nimsa.sbx.dicom.streams

import akka.util.ByteString
import org.dcm4che3.data.Attributes
import se.nimsa.dicom._
import se.nimsa.dcm4che.streams.toCheVR
import se.nimsa.dicom.VR.VR

object DicomTestData {

  val preamble: ByteString = ByteString.fromArray(new Array[Byte](128)) ++ ByteString('D', 'I', 'C', 'M')
  def fmiGroupLength(fmis: ByteString*): ByteString = ByteString(2, 0, 0, 0, 85, 76, 4, 0) ++ intToBytesLE(fmis.map(_.length).sum)
  val tsuidExplicitLE: ByteString = ByteString(2, 0, 16, 0, 85, 73, 20, 0, '1', '.', '2', '.', '8', '4', '0', '.', '1', '0', '0', '0', '8', '.', '1', '.', '2', '.', '1', 0)
  val supportedMediaStorageSOPClassUID: ByteString = ByteString(2, 0, 2, 0, 85, 73, 26, 0) ++ ByteString.fromArray("1.2.840.10008.5.1.4.1.1.20".toCharArray.map(_.toByte))
  val unsupportedMediaStorageSOPClassUID: ByteString = ByteString(2, 0, 2, 0, 85, 73, 26, 0) ++ ByteString.fromArray("1.2.840.10008.5.1.4.1.1.7".toCharArray.map(_.toByte)) ++ ByteString(0)
  val unknownMediaStorageSOPClassUID: ByteString = ByteString(2, 0, 2, 0, 85, 73, 8, 0) ++ ByteString.fromArray("1.2.3.4".toCharArray.map(_.toByte)) ++ ByteString(0)
  val patientNameJohnDoe: ByteString = ByteString(16, 0, 16, 0, 80, 78, 8, 0, 'J', 'o', 'h', 'n', '^', 'D', 'o', 'e')
  val supportedSOPClassUID: ByteString = ByteString(8, 0, 22, 0, 85, 73, 26, 0) ++ ByteString.fromArray("1.2.840.10008.5.1.4.1.1.20".toCharArray.map(_.toByte))
  val unsupportedSOPClassUID: ByteString = ByteString(8, 0, 22, 0, 85, 73, 26, 0) ++ ByteString.fromArray("1.2.840.10008.5.1.4.1.1.7".toCharArray.map(_.toByte)) ++ ByteString(0)

  val metaInformation: Attributes = {
    val meta = new Attributes()
    meta.setString(Tag.TransferSyntaxUID, VR.UI, UID.ExplicitVRLittleEndian)
    meta
  }

  def createAttributes: Attributes = {
    val attributes = new Attributes()
    attributes.setString(Tag.PatientName, VR.PN, "pn")
    attributes.setString(Tag.PatientID, VR.LO, "pid")
    attributes.setString(Tag.StudyInstanceUID, VR.LO, "stuid")
    attributes.setString(Tag.SeriesInstanceUID, VR.LO, "seuid")
    attributes.setString(Tag.FrameOfReferenceUID, VR.LO, "foruid")
    attributes.setString(Tag.Allergies, VR.LO, "allergies")
    attributes.setString(Tag.PatientIdentityRemoved, VR.CS, "NO")
    attributes
  }

  def toAsciiBytes(s: String, vr: VR): ByteString = padToEvenLength(ByteString(s), vr)

}
