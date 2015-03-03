package se.vgregion.app

import spray.json._
import se.vgregion.dicom.DicomProperty
import se.vgregion.dicom.DicomPropertyValue._
import se.vgregion.dicom.DicomPropertyValue
import se.vgregion.dicom.DicomHierarchy._
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.box.BoxProtocol._
import se.vgregion.log.LogProtocol._

trait JsonFormats extends DefaultJsonProtocol {

  implicit val watchDirectoryFormat = jsonFormat1(WatchDirectory)
  implicit val unWatchDirectoryFormat = jsonFormat1(UnWatchDirectory)
  implicit val watchedDirectoryFormat = jsonFormat2(WatchedDirectory)

  implicit val scpDataFormat = jsonFormat4(ScpData)

  implicit val remoteBoxFormat = jsonFormat2(RemoteBox)
  implicit val remoteBoxNameFormat = jsonFormat1(RemoteBoxName)
  implicit val boxBaseUrlFormat = jsonFormat1(BoxBaseUrl)

  implicit val updateInboxFormat = jsonFormat4(UpdateInbox)

  implicit val imageIdFormat = jsonFormat1(ImageId)
  implicit val imageIdsFormat = jsonFormat1(ImageIds)

  implicit object BoxSendMethodFormat extends JsonFormat[BoxSendMethod] {
    def write(obj: BoxSendMethod) = JsString(obj.toString)

    def read(json: JsValue): BoxSendMethod = json match {
      case JsString(string) => BoxSendMethod.withName(string)
      case _                => deserializationError("Enumeration expected")
    }
  }

  implicit val boxFormat = jsonFormat6(Box)

  implicit val outboxEntryFormat = jsonFormat7(OutboxEntry)
  implicit val outboxEntryInfoFormat = jsonFormat7(OutboxEntryInfo)
  implicit val inboxEntryInfoFormat = jsonFormat4(InboxEntryInfo)

  implicit val generateBoxBaseUrlFormat = jsonFormat1(GenerateBoxBaseUrl)

  implicit val addScpDataFormat = jsonFormat3(AddScp)

  implicit object RoleFormat extends JsonFormat[Role] {
    def write(obj: Role) = JsString(obj.toString)

    def read(json: JsValue): Role = json match {
      case JsString(string) => Role.valueOf(string)
      case _                => Collaborator
    }
  }

  implicit val clearTextUserFormat = jsonFormat3(ClearTextUser)

  implicit val patientNameFormat = jsonFormat1(PatientName)
  implicit val patientIdFormat = jsonFormat1(PatientID)
  implicit val patientBirthDateFormat = jsonFormat1(PatientBirthDate)
  implicit val patientSexFormat = jsonFormat1(PatientSex)

  implicit val patientFormat = jsonFormat5(Patient)

  implicit val studyInstanceUidFormat = jsonFormat1(StudyInstanceUID)
  implicit val studyDescriptionFormat = jsonFormat1(StudyDescription)
  implicit val studyDateFormat = jsonFormat1(StudyDate)
  implicit val studyIdFormat = jsonFormat1(StudyID)
  implicit val accessionNumberFormat = jsonFormat1(AccessionNumber)
  implicit val patientAgeFormat = jsonFormat1(PatientAge)

  implicit val studyFormat = jsonFormat8(Study)

  implicit val manufacturerFormat = jsonFormat1(Manufacturer)
  implicit val stationNameFormat = jsonFormat1(StationName)

  implicit val equipmentFormat = jsonFormat3(Equipment)

  implicit val frameOfReferenceUidFormat = jsonFormat1(FrameOfReferenceUID)

  implicit val frameOfReferenceFormat = jsonFormat2(FrameOfReference)

  implicit val seriesInstanceUidFormat = jsonFormat1(SeriesInstanceUID)
  implicit val seriesDescriptionFormat = jsonFormat1(SeriesDescription)
  implicit val seriesDateFormat = jsonFormat1(SeriesDate)
  implicit val modalityFormat = jsonFormat1(Modality)
  implicit val protocolNameFormat = jsonFormat1(ProtocolName)
  implicit val bodyPartExaminedFormat = jsonFormat1(BodyPartExamined)

  implicit val seriesFormat = jsonFormat10(Series)
  implicit val flatSeriesFormat = jsonFormat6(FlatSeries)

  implicit val sopInstanceUidFormat = jsonFormat1(SOPInstanceUID)
  implicit val imageTypeFormat = jsonFormat1(ImageType)

  implicit val imageFormat = jsonFormat4(Image)

  implicit val fileNameFormat = jsonFormat1(FileName)
  implicit val imageFileFormat = jsonFormat2(ImageFile)
  implicit val imageAttributeFormat = jsonFormat9(ImageAttribute)
  
  implicit val imagesFormat = jsonFormat1(Images)
  
  implicit val numberOfImageFramesFormat = jsonFormat4(ImageInformation)
  
  implicit object LogEntryTypeFormat extends JsonFormat[LogEntryType] {
    def write(obj: LogEntryType) = JsString(obj.toString)

    def read(json: JsValue): LogEntryType = json match {
      case JsString(string) => LogEntryType.withName(string)
      case _                => deserializationError("Enumeration expected")
    }
  }
  
  implicit val logEntryFormat = jsonFormat4(LogEntry)
}