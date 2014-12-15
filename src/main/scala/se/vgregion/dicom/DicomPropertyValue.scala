package se.vgregion.dicom

import spray.json.DefaultJsonProtocol

trait DicomPropertyValue { 
  def property: DicomProperty
  def value: String 
}

object DicomPropertyValue {

  case class PatientName(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientName }
  case class PatientID(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientID }
  case class PatientBirthDate(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientBirthDate }
  case class PatientSex(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientSex }

  case class StudyInstanceUID(value: String) extends DicomPropertyValue { def property = DicomProperty.StudyInstanceUID }
  case class StudyDescription(value: String) extends DicomPropertyValue { def property = DicomProperty.StudyDescription }
  case class StudyDate(value: String) extends DicomPropertyValue { def property = DicomProperty.StudyDate }
  case class StudyID(value: String) extends DicomPropertyValue { def property = DicomProperty.StudyID }
  case class AccessionNumber(value: String) extends DicomPropertyValue { def property = DicomProperty.AccessionNumber }

  case class SeriesInstanceUID(value: String) extends DicomPropertyValue { def property = DicomProperty.SeriesInstanceUID }
  case class SeriesNumber(value: String) extends DicomPropertyValue { def property = DicomProperty.SeriesNumber }
  case class SeriesDescription(value: String) extends DicomPropertyValue { def property = DicomProperty.SeriesDescription }
  case class SeriesDate(value: String) extends DicomPropertyValue { def property = DicomProperty.SeriesDate }
  case class Modality(value: String) extends DicomPropertyValue { def property = DicomProperty.Modality }
  case class ProtocolName(value: String) extends DicomPropertyValue { def property = DicomProperty.ProtocolName }
  case class BodyPartExamined(value: String) extends DicomPropertyValue { def property = DicomProperty.BodyPartExamined }

  case class SOPInstanceUID(value: String) extends DicomPropertyValue { def property = DicomProperty.SOPInstanceUID }
  case class ImageType(value: String) extends DicomPropertyValue { def property = DicomProperty.ImageType }

  case class Manufacturer(value: String) extends DicomPropertyValue { def property = DicomProperty.Manufacturer }
  case class StationName(value: String) extends DicomPropertyValue { def property = DicomProperty.StationName }

  case class FrameOfReferenceUID(value: String) extends DicomPropertyValue { def property = DicomProperty.FrameOfReferenceUID }
  
  object PatientName extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(PatientName.apply) }
  object PatientID extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(PatientID.apply) }
  object PatientBirthDate extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(PatientBirthDate.apply) }
  object PatientSex extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(PatientSex.apply) }

  object StudyInstanceUID extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(StudyInstanceUID.apply) }
  object StudyDescription extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(StudyDescription.apply) }
  object StudyDate extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(StudyDate.apply) }
  object StudyID extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(StudyID.apply) }
  object AccessionNumber extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(AccessionNumber.apply) }

  object SeriesInstanceUID extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(SeriesInstanceUID.apply) }
  object SeriesNumber extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(SeriesNumber.apply) }
  object SeriesDescription extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(SeriesDescription.apply) }
  object SeriesDate extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(SeriesDate.apply) }
  object Modality extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(Modality.apply) }
  object ProtocolName extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(ProtocolName.apply) }
  object BodyPartExamined extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(BodyPartExamined.apply) }

  object SOPInstanceUID extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(SOPInstanceUID.apply) }
  object ImageType extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(ImageType.apply) }

  object Manufacturer extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(Manufacturer.apply) }
  object StationName extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(StationName.apply) }

  object FrameOfReferenceUID extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(FrameOfReferenceUID.apply) }
}
