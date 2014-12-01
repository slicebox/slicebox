package se.vgregion.dicom

import org.dcm4che3.data.Tag
import spray.json.DefaultJsonProtocol

object Attributes {

  case class PatientName(value: String) extends AnyVal 
  case class PatientID(value: String) extends AnyVal 
  case class PatientBirthDate(value: String) extends AnyVal
  case class PatientSex(value: String) extends AnyVal

  case class StudyInstanceUID(value: String) extends AnyVal
  case class StudyDescription(value: String) extends AnyVal
  case class StudyDate(value: String) extends AnyVal
  case class StudyID(value: String) extends AnyVal
  case class AccessionNumber(value: String) extends AnyVal
  
  case class SeriesInstanceUID(value: String) extends AnyVal
  case class SeriesNumber(value: String) extends AnyVal
  case class SeriesDescription(value: String) extends AnyVal
  case class SeriesDate(value: String) extends AnyVal
  case class Modality(value: String) extends AnyVal 
  case class ProtocolName(value: String) extends AnyVal
  case class BodyPartExamined(value: String) extends AnyVal 

  case class SOPInstanceUID(value: String) extends AnyVal
  case class ImageType(value: String) extends AnyVal
  
  case class Manufacturer(value: String) extends AnyVal
  case class StationName(value: String) extends AnyVal
  
  case class FrameOfReferenceUID(value: String) extends AnyVal
  
  object PatientName extends DefaultJsonProtocol { val name = "PatientName"; val tag = Tag.PatientName; implicit val format = DefaultJsonProtocol.jsonFormat1(PatientName.apply) }
  object PatientID extends DefaultJsonProtocol { val name = "PatientID"; val tag = Tag.PatientID; implicit val format = DefaultJsonProtocol.jsonFormat1(PatientID.apply) }
  object PatientBirthDate extends DefaultJsonProtocol { val name = "PatientBirthDate"; val tag = Tag.PatientBirthDate; implicit val format = DefaultJsonProtocol.jsonFormat1(PatientBirthDate.apply) }
  object PatientSex extends DefaultJsonProtocol { val name = "PatientSex"; val tag = Tag.PatientSex; implicit val format = DefaultJsonProtocol.jsonFormat1(PatientSex.apply) }

  object StudyInstanceUID extends DefaultJsonProtocol { val name = "StudyInstanceUID"; val tag = Tag.StudyInstanceUID; implicit val format = DefaultJsonProtocol.jsonFormat1(StudyInstanceUID.apply) }
  object StudyDescription extends DefaultJsonProtocol { val name = "StudyDescription"; val tag = Tag.StudyDescription; implicit val format = DefaultJsonProtocol.jsonFormat1(StudyDescription.apply) }
  object StudyDate extends DefaultJsonProtocol { val name = "StudyDate"; val tag = Tag.StudyDate; implicit val format = DefaultJsonProtocol.jsonFormat1(StudyDate.apply) }
  object StudyID extends DefaultJsonProtocol { val name = "StudyID"; val tag = Tag.StudyID; implicit val format = DefaultJsonProtocol.jsonFormat1(StudyID.apply) }
  object AccessionNumber extends DefaultJsonProtocol { val name = "AccessionNumber"; val tag = Tag.AccessionNumber; implicit val format = DefaultJsonProtocol.jsonFormat1(AccessionNumber.apply) }
  
  object SeriesInstanceUID extends DefaultJsonProtocol { val name = "SeriesInstanceUID"; val tag = Tag.SeriesInstanceUID; implicit val format = DefaultJsonProtocol.jsonFormat1(SeriesInstanceUID.apply) }
  object SeriesDescription extends DefaultJsonProtocol { val name = "SeriesDescription"; val tag = Tag.SeriesDescription; implicit val format = DefaultJsonProtocol.jsonFormat1(SeriesDescription.apply) }
  object SeriesDate extends DefaultJsonProtocol { val name = "SeriesDate"; val tag = Tag.SeriesDate; implicit val format = DefaultJsonProtocol.jsonFormat1(SeriesDate.apply) }
  object Modality extends DefaultJsonProtocol { val name = "Modality"; val tag = Tag.Modality; implicit val format = DefaultJsonProtocol.jsonFormat1(Modality.apply) }
  object ProtocolName extends DefaultJsonProtocol { val name = "ProtocolName"; val tag = Tag.ProtocolName; implicit val format = DefaultJsonProtocol.jsonFormat1(ProtocolName.apply) }
  object BodyPartExamined extends DefaultJsonProtocol { val name = "BodyPartExamined"; val tag = Tag.BodyPartExamined; implicit val format = DefaultJsonProtocol.jsonFormat1(BodyPartExamined.apply) }

  object SOPInstanceUID extends DefaultJsonProtocol { val name = "SOPInstanceUID"; val tag = Tag.SOPInstanceUID; implicit val format = DefaultJsonProtocol.jsonFormat1(SOPInstanceUID.apply) }
  object ImageType extends DefaultJsonProtocol { val name = "ImageType"; val tag = Tag.ImageType; implicit val format = DefaultJsonProtocol.jsonFormat1(ImageType.apply) }
  
  object Manufacturer extends DefaultJsonProtocol { val name = "Manufacturer"; val tag = Tag.Manufacturer; implicit val format = DefaultJsonProtocol.jsonFormat1(Manufacturer.apply) }
  object StationName extends DefaultJsonProtocol { val name = "StationName"; val tag = Tag.StationName; implicit val format = DefaultJsonProtocol.jsonFormat1(StationName.apply) }
  
  object FrameOfReferenceUID extends DefaultJsonProtocol { val name = "FrameOfReferenceUID"; val tag = Tag.FrameOfReferenceUID; implicit val format = DefaultJsonProtocol.jsonFormat1(FrameOfReferenceUID.apply) }
}
