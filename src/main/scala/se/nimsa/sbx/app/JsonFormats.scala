/*
 * Copyright 2015 Karl Sjöstrand
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.app

import spray.json._
import se.nimsa.sbx.dicom.DicomProperty
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.dicom.DicomPropertyValue
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.log.LogProtocol._
import se.nimsa.sbx.app.UserProtocol._
import spray.routing.authentication.UserPass

trait JsonFormats extends DefaultJsonProtocol {

  implicit val watchDirectoryFormat = jsonFormat1(WatchDirectory)
  implicit val unWatchDirectoryFormat = jsonFormat1(UnWatchDirectory)
  implicit val watchedDirectoryFormat = jsonFormat2(WatchedDirectory)

  implicit val scpDataFormat = jsonFormat4(ScpData)
  implicit val scuDataFormat = jsonFormat5(ScuData)

  implicit val remoteBoxFormat = jsonFormat2(RemoteBox)
  implicit val remoteBoxNameFormat = jsonFormat1(RemoteBoxName)
  implicit val boxBaseUrlFormat = jsonFormat1(BoxBaseUrl)

  implicit val updateInboxFormat = jsonFormat4(UpdateInbox)

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

  implicit val attributeValueMappingFormat = jsonFormat3(BoxSendTagValue)
  implicit val sendImagesDataFormat = jsonFormat2(BoxSendData)
  
  implicit val generateBoxBaseUrlFormat = jsonFormat1(GenerateBoxBaseUrl)

  implicit val addScpDataFormat = jsonFormat3(AddScp)
  implicit val addScuDataFormat = jsonFormat4(AddScu)

  implicit object RoleFormat extends JsonFormat[UserRole] {
    def write(obj: UserRole) = JsString(obj.toString)

    def read(json: JsValue): UserRole = json match {
      case JsString(string) => UserRole.withName(string)
      case _                => deserializationError("Enumeration expected")
    }
  }

  implicit val clearTextUserFormat = jsonFormat3(ClearTextUser)
  implicit val apiUserFormat = jsonFormat4(ApiUser)
  implicit val userPassFormat = jsonFormat2(UserPass)
  implicit val loginResultFormat = jsonFormat3(LoginResult)
  implicit val authTokenFormat = jsonFormat1(AuthToken)
  
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
  implicit val instanceNumberFormat = jsonFormat1(InstanceNumber)

  implicit val imageFormat = jsonFormat5(Image)

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
  
  implicit val logEntryFormat = jsonFormat5(LogEntry)
  
  implicit val seriesDatasetFormat = jsonFormat2(SeriesDataset)
  
  implicit object QueryOperatorFormat extends JsonFormat[QueryOperator] {
    def write(obj: QueryOperator) = JsString(obj.toString)

    def read(json: JsValue): QueryOperator = json match {
      case JsString(string) => QueryOperator.withName(string)
      case _                => deserializationError("Enumeration expected")
    }
  }
  
  implicit val queryPropertyFormat = jsonFormat3(QueryProperty)
  implicit val queryFormat = jsonFormat5(Query)
}
