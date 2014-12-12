package se.vgregion.dicom

import spray.json.DefaultJsonProtocol

trait DicomPropertyValue { 
  def name: String
  def value: String 
}

object DicomPropertyValue {

  case class PatientName(value: String) extends DicomPropertyValue { def name = DicomProperty.PatientName.name }
  case class PatientID(value: String) extends DicomPropertyValue { def name = DicomProperty.PatientID.name }
  case class PatientBirthDate(value: String) extends DicomPropertyValue { def name = DicomProperty.PatientBirthDate.name }
  case class PatientSex(value: String) extends DicomPropertyValue { def name = DicomProperty.PatientSex.name }

  case class StudyInstanceUID(value: String) extends DicomPropertyValue { def name = DicomProperty.StudyInstanceUID.name }
  case class StudyDescription(value: String) extends DicomPropertyValue { def name = DicomProperty.StudyDescription.name }
  case class StudyDate(value: String) extends DicomPropertyValue { def name = DicomProperty.StudyDate.name }
  case class StudyID(value: String) extends DicomPropertyValue { def name = DicomProperty.StudyID.name }
  case class AccessionNumber(value: String) extends DicomPropertyValue { def name = DicomProperty.AccessionNumber.name }

  case class SeriesInstanceUID(value: String) extends DicomPropertyValue { def name = DicomProperty.SeriesInstanceUID.name }
  case class SeriesNumber(value: String) extends DicomPropertyValue { def name = DicomProperty.SeriesNumber.name }
  case class SeriesDescription(value: String) extends DicomPropertyValue { def name = DicomProperty.SeriesDescription.name }
  case class SeriesDate(value: String) extends DicomPropertyValue { def name = DicomProperty.SeriesDate.name }
  case class Modality(value: String) extends DicomPropertyValue { def name = DicomProperty.Modality.name }
  case class ProtocolName(value: String) extends DicomPropertyValue { def name = DicomProperty.ProtocolName.name }
  case class BodyPartExamined(value: String) extends DicomPropertyValue { def name = DicomProperty.BodyPartExamined.name }

  case class SOPInstanceUID(value: String) extends DicomPropertyValue { def name = DicomProperty.SOPInstanceUID.name }
  case class ImageType(value: String) extends DicomPropertyValue { def name = DicomProperty.ImageType.name }

  case class Manufacturer(value: String) extends DicomPropertyValue { def name = DicomProperty.Manufacturer.name }
  case class StationName(value: String) extends DicomPropertyValue { def name = DicomProperty.StationName.name }

  case class FrameOfReferenceUID(value: String) extends DicomPropertyValue { def name = DicomProperty.FrameOfReferenceUID.name }
  
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
