package se.vgregion.app

import spray.json._
import se.vgregion.dicom.DicomPropertyValue._
import se.vgregion.dicom.DicomHierarchy._
import se.vgregion.dicom.DicomProtocol._

trait JsonFormats extends DefaultJsonProtocol {

  implicit val watchDirectoryFormat = jsonFormat1(WatchDirectory)
  implicit val unWatchDirectoryFormat = jsonFormat1(UnWatchDirectory)

  implicit val scpDataFormat = jsonFormat3(ScpData)

  implicit val addScpDataFormat = jsonFormat1(AddScp)
  
  implicit object RoleFormat extends JsonFormat[Role] {
    def write(obj: Role) = JsString(obj.toString)

    def read(json: JsValue): Role = json match {
      case JsString(string) => Role.valueOf(string)
      case _ => Collaborator
    }
  }
  
  implicit val clearTextUserFormat = jsonFormat3(ClearTextUser)
  
  implicit val patientNameFormat = jsonFormat1(PatientName)
  implicit val patientIdFormat = jsonFormat1(PatientID)
  implicit val patientBirthDateFormat = jsonFormat1(PatientBirthDate)
  implicit val patientSexFormat = jsonFormat1(PatientSex)

  implicit val patientFormat = jsonFormat4(Patient)
  
  implicit val studyInstanceUidFormat = jsonFormat1(StudyInstanceUID)
  implicit val studyDescriptionFormat = jsonFormat1(StudyDescription)
  implicit val studyDateFormat = jsonFormat1(StudyDate)
  implicit val studyIdFormat = jsonFormat1(StudyID)
  implicit val accessionNumberFormat = jsonFormat1(AccessionNumber)
  
  implicit val studyFormat = jsonFormat6(Study)
  
  implicit val manufacturerFormat = jsonFormat1(Manufacturer)
  implicit val stationNameFormat = jsonFormat1(StationName)
  
  implicit val equipmentFormat = jsonFormat2(Equipment)
  
  implicit val frameOfReferenceUidFormat = jsonFormat1(FrameOfReferenceUID)
  
  implicit val frameOfReferenceFormat = jsonFormat1(FrameOfReference)
  
  implicit val seriesInstanceUidFormat = jsonFormat1(SeriesInstanceUID)
  implicit val seriesDescriptionFormat = jsonFormat1(SeriesDescription)
  implicit val seriesDateFormat = jsonFormat1(SeriesDate)
  implicit val modalityFormat = jsonFormat1(Modality)
  implicit val protocolNameFormat = jsonFormat1(ProtocolName)
  implicit val bodyPartExaminedFormat = jsonFormat1(BodyPartExamined)
  
  implicit val seriesFormat = jsonFormat9(Series)
  
  implicit val sopInstanceUidFormat = jsonFormat1(SOPInstanceUID)
  implicit val imageTypeFormat = jsonFormat1(ImageType)
  
  implicit val imageFormat = jsonFormat3(Image)
  
  implicit val imagesFormat = jsonFormat1(Images)
}