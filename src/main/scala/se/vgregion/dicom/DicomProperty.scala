package se.vgregion.dicom

import org.dcm4che3.data.Tag
import spray.json.DefaultJsonProtocol

case class DicomProperty(val name: String, val dicomTag: Int)

object DicomProperty {
  object PatientName extends DicomProperty("PatientName", Tag.PatientName) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object PatientID extends DicomProperty("PatientID", Tag.PatientID) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object PatientBirthDate extends DicomProperty("PatientBirthDate", Tag.PatientBirthDate) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object PatientSex extends DicomProperty("PatientSex", Tag.PatientSex) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }

  object StudyInstanceUID extends DicomProperty("StudyInstanceUID", Tag.StudyInstanceUID) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object StudyDescription extends DicomProperty("StudyDescription", Tag.StudyDescription) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object StudyDate extends DicomProperty("StudyDate", Tag.StudyDate) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object StudyID extends DicomProperty("StudyID", Tag.StudyID) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object AccessionNumber extends DicomProperty("AccessionNumber", Tag.AccessionNumber) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }

  object SeriesInstanceUID extends DicomProperty("SeriesInstanceUID", Tag.SeriesInstanceUID) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object SeriesNumber extends DicomProperty("SeriesNumber", Tag.SeriesNumber) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object SeriesDescription extends DicomProperty("SeriesDescription", Tag.SeriesDescription) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object SeriesDate extends DicomProperty("SeriesDate", Tag.SeriesDate) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object Modality extends DicomProperty("Modality", Tag.Modality) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object ProtocolName extends DicomProperty("ProtocolName", Tag.ProtocolName) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object BodyPartExamined extends DicomProperty("BodyPartExamined", Tag.BodyPartExamined) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }

  object SOPInstanceUID extends DicomProperty("SOPInstanceUID", Tag.SOPInstanceUID) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object ImageType extends DicomProperty("ImageType", Tag.ImageType) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }

  object Manufacturer extends DicomProperty("Manufacturer", Tag.Manufacturer) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
  object StationName extends DicomProperty("StationName", Tag.StationName) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }

  object FrameOfReferenceUID extends DicomProperty("FrameOfReferenceUID", Tag.FrameOfReferenceUID) with DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat2(apply) }
}
