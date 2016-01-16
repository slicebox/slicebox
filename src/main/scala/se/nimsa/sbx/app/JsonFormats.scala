/*
 * Copyright 2016 Lars Edenbrandt
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
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.directory.DirectoryWatchProtocol._
import se.nimsa.sbx.scp.ScpProtocol._
import se.nimsa.sbx.scu.ScuProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.log.LogProtocol._
import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import se.nimsa.sbx.forwarding.ForwardingProtocol._
import GeneralProtocol._
import spray.routing.authentication.UserPass
import se.nimsa.sbx.dicom.ImageAttribute

trait JsonFormats extends DefaultJsonProtocol {

  implicit val unWatchDirectoryFormat = jsonFormat1(UnWatchDirectory)
  implicit val watchedDirectoryFormat = jsonFormat3(WatchedDirectory)

  implicit val scpDataFormat = jsonFormat4(ScpData)
  implicit val scuDataFormat = jsonFormat5(ScuData)

  implicit val remoteBoxFormat = jsonFormat2(RemoteBox)
  implicit val remoteBoxConnectionDataFormat = jsonFormat1(RemoteBoxConnectionData)

  implicit object SourceTypeFormat extends JsonFormat[SourceType] {
    def write(obj: SourceType) = JsString(obj.toString)

    def read(json: JsValue): SourceType = json match {
      case JsString(string) => SourceType.withName(string)
      case _                => deserializationError("Enumeration expected")
    }
  }

  implicit object DestinationTypeFormat extends JsonFormat[DestinationType] {
    def write(obj: DestinationType) = JsString(obj.toString)

    def read(json: JsValue): DestinationType = json match {
      case JsString(string) => DestinationType.withName(string)
      case _                => deserializationError("Enumeration expected")
    }
  }

  implicit object BoxSendMethodFormat extends JsonFormat[BoxSendMethod] {
    def write(obj: BoxSendMethod) = JsString(obj.toString)

    def read(json: JsValue): BoxSendMethod = json match {
      case JsString(string) => BoxSendMethod.withName(string)
      case _                => deserializationError("Enumeration expected")
    }
  }

  implicit object TransactionStatusFormat extends JsonFormat[TransactionStatus] {
    def write(obj: TransactionStatus) = JsString(obj.toString)

    def read(json: JsValue): TransactionStatus = json match {
      case JsString(string) => TransactionStatus.withName(string)
      case _                => deserializationError("Enumeration expected")
    }
  }

  implicit val boxFormat = jsonFormat6(Box)

  implicit val outgoingEntryFormat = jsonFormat7(OutgoingTransaction)
  implicit val outgoingImageFormat = jsonFormat5(OutgoingImage)
  implicit val outgoingEntryImageFormat = jsonFormat2(OutgoingTransactionImage)
  
  implicit val failedOutgoingEntryFormat = jsonFormat2(FailedOutgoingTransactionImage)
  
  implicit val incomingEntryFormat = jsonFormat8(IncomingTransaction)

  implicit val tagValueFormat = jsonFormat2(TagValue)
  implicit val anonymizationKeyFormat = jsonFormat18(AnonymizationKey)

  implicit val entityTagValueFormat = jsonFormat2(ImageTagValues)

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
  implicit val userInfoFormat = jsonFormat3(UserInfo)
  
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

  implicit val frameOfReferenceUidFormat = jsonFormat1(FrameOfReferenceUID)

  implicit val seriesInstanceUidFormat = jsonFormat1(SeriesInstanceUID)
  implicit val seriesDescriptionFormat = jsonFormat1(SeriesDescription)
  implicit val seriesDateFormat = jsonFormat1(SeriesDate)
  implicit val modalityFormat = jsonFormat1(Modality)
  implicit val protocolNameFormat = jsonFormat1(ProtocolName)
  implicit val bodyPartExaminedFormat = jsonFormat1(BodyPartExamined)

  implicit val sourceFormat = jsonFormat3(Source)
  implicit val sourceRefFormat = jsonFormat2(SourceRef)
  
  implicit val destinationFormat = jsonFormat3(Destination)
  
  implicit val seriesFormat = jsonFormat11(Series)
  implicit val flatSeriesFormat = jsonFormat4(FlatSeries)

  implicit val sopInstanceUidFormat = jsonFormat1(SOPInstanceUID)
  implicit val imageTypeFormat = jsonFormat1(ImageType)
  implicit val instanceNumberFormat = jsonFormat1(InstanceNumber)

  implicit val imageFormat = jsonFormat5(Image)

  implicit val fileNameFormat = jsonFormat1(FileName)
  implicit val imageAttributeFormat = jsonFormat11(ImageAttribute)
  
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
  
  implicit object QueryOperatorFormat extends JsonFormat[QueryOperator] {
    def write(obj: QueryOperator) = JsString(obj.toString)

    def read(json: JsValue): QueryOperator = json match {
      case JsString(string) => QueryOperator.withName(string)
      case _                => deserializationError("Enumeration expected")
    }
  }
  
  implicit val queryOrderFormat = jsonFormat2(QueryOrder)
  implicit val queryPropertyFormat = jsonFormat3(QueryProperty)
  implicit val queryFiltersFormat = jsonFormat3(QueryFilters)
  implicit val queryFormat = jsonFormat5(Query)
  
  implicit val anonymizationKeyQueryFormat = jsonFormat4(AnonymizationKeyQuery)
  
  implicit val seriesTypeFormat = jsonFormat2(SeriesType)
  
  implicit val seriesTagFormat = jsonFormat2(SeriesTag)
  
  implicit val seriesTypeRuleFormat = jsonFormat2(SeriesTypeRule)
  
  implicit val seriesTypeRuleAttributeFormat = jsonFormat7(SeriesTypeRuleAttribute)
  
  implicit val forwardingRuleFormat = jsonFormat4(ForwardingRule)
}
