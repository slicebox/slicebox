/*
 * Copyright 2014 Lars Edenbrandt
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

import java.util.Base64

import akka.util.ByteString
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import se.nimsa.dicom.data.TagPath._
import se.nimsa.dicom.data.{Dictionary, Multiplicity, TagPath}
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.dicom.ImageAttribute
import se.nimsa.sbx.directory.DirectoryWatchProtocol._
import se.nimsa.sbx.filtering.FilteringProtocol.{SourceTagFilter, TagFilterSpec, TagFilterType}
import se.nimsa.sbx.forwarding.ForwardingProtocol._
import se.nimsa.sbx.importing.ImportProtocol._
import se.nimsa.sbx.log.LogProtocol._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.scp.ScpProtocol._
import se.nimsa.sbx.scu.ScuProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.user.UserProtocol._

import scala.util.{Failure, Success}

trait JsonFormats {

  private def enumFormat[A](f: String => A) = Format(Reads[A] {
    case JsString(string) => JsSuccess(f(string))
    case _ => JsError("Enumeration expected")
  }, Writes[A](a => JsString(a.toString)))

  implicit lazy val tagPathReads: Reads[TagPath] =
    (
      (__ \ "tag").readNullable[Int] and
        (__ \ "name").readNullable[String] and
        (__ \ "item").readNullable[String] and
        (__ \ "previous").lazyReadNullable[TagPath](tagPathReads)
      ) ((tagMaybe, nameMaybe, itemMaybe, previousPath) =>
      tagMaybe
        .orElse(nameMaybe
          .flatMap(name => try Option(Dictionary.tagOf(name)) catch { case _: Throwable => None }))
        .map { tag =>
          val previous = previousPath match {
            case Some(t: TagPathTrunk) => Some(t)
            case _ => None
          }
          itemMaybe match {
            case Some("*") =>
              previous.map(p => p.thenSequence(tag)).getOrElse(TagPath.fromSequence(tag))
            case Some(itemString) =>
              val item = try Option(Integer.parseInt(itemString)) catch {
                case _: Throwable => None
              }
              item.map(i => previous.map(p => p.thenSequence(tag, i)).getOrElse(TagPath.fromSequence(tag, i)))
                .getOrElse(previous.map(p => p.thenTag(tag)).getOrElse(TagPath.fromTag(tag)))
            case None =>
              previous.map(p => p.thenTag(tag)).getOrElse(TagPath.fromTag(tag))
          }
        }
        .map(Success.apply)
        .getOrElse(Failure(new IllegalArgumentException())))
      .collect(JsonValidationError(s"Could not parse tag path, must supply either tag or name")) {
        case Success(tag) => tag
      }

  implicit lazy val tagPathTagReads: Reads[TagPathTag] =
    tagPathReads.collect(JsonValidationError("Could not parse tag path tag")) {
      case tp: TagPathTag => tp
    }

  implicit lazy val tagPathSequenceItemReads: Reads[TagPathSequenceItem] =
    tagPathReads.collect(JsonValidationError("Could not parse tag path sequence")) {
      case tp: TagPathSequenceItem => tp
    }

  implicit lazy val tagPathSequenceAnyReads: Reads[TagPathSequenceAny] =
    tagPathReads.collect(JsonValidationError("Could not parse tag path sequence")) {
      case tp: TagPathSequenceAny => tp
    }

  implicit lazy val tagPathWrites: Writes[TagPath] = {
    val trunkToOption: TagPathTrunk => Option[TagPathTrunk] = {
      case EmptyTagPath => None
      case p => Some(p)
    }

    val tagPathToTuple: TagPath => (Int, String, Option[String], Option[TagPathTrunk]) = {
      case sequenceItem: TagPathSequenceItem => (sequenceItem.tag, Dictionary.keywordOf(sequenceItem.tag), Some(sequenceItem.item.toString), trunkToOption(sequenceItem.previous))
      case sequenceAny: TagPathSequenceAny => (sequenceAny.tag, Dictionary.keywordOf(sequenceAny.tag), Some("*"), trunkToOption(sequenceAny.previous))
      case tagPath: TagPath => (tagPath.tag, Dictionary.keywordOf(tagPath.tag), None, trunkToOption(tagPath.previous))
    }

    (
      (__ \ "tag").write[Int] and
        (__ \ "name").write[String] and
        (__ \ "item").writeNullable[String] and
        (__ \ "previous").lazyWriteNullable[TagPathTrunk](tagPathWrites)
      ) (tagPathToTuple)
  }

  implicit val tagMappingFormat: Format[TagMapping] = Format[TagMapping](
    (
      (__ \ "tagPath").read[TagPath] and
        (__ \ "value").read[String]) ((tagPath, valueString) => TagMapping(tagPath.asInstanceOf[TagPathTag], ByteString(Base64.getDecoder.decode(valueString)))
    ),
    (
      (__ \ "tagPath").write[TagPath] and
        (__ \ "value").write[String]) (tagMapping => (tagMapping.tagPath, Base64.getEncoder.encodeToString(tagMapping.value.toArray))
    )
  )

  implicit val unWatchDirectoryFormat: Format[UnWatchDirectory] = Json.format[UnWatchDirectory]
  implicit val watchedDirectoryFormat: Format[WatchedDirectory] = Json.format[WatchedDirectory]

  implicit val scpDataFormat: Format[ScpData] = Json.format[ScpData]
  implicit val scuDataFormat: Format[ScuData] = Json.format[ScuData]

  implicit val remoteBoxFormat: Format[RemoteBox] = Json.format[RemoteBox]
  implicit val remoteBoxConnectionDataFormat: Format[RemoteBoxConnectionData] = Json.format[RemoteBoxConnectionData]

  implicit val systemInformationFormat: Format[SystemInformation] = Json.format[SystemInformation]

  implicit val sourceTypeFormat: Format[SourceType] = enumFormat(SourceType.withName)

  implicit val destinationTypeFormat: Format[DestinationType] = enumFormat(DestinationType.withName)

  implicit val boxSendMethodFormat: Format[BoxSendMethod] = enumFormat(BoxSendMethod.withName)

  implicit val transactionStatusFormat: Format[TransactionStatus] = enumFormat(TransactionStatus.withName)

  implicit val boxFormat: Format[Box] = Json.format[Box]

  implicit val outgoingEntryFormat: Format[OutgoingTransaction] = Json.format[OutgoingTransaction]
  implicit val outgoingImageFormat: Format[OutgoingImage] = Json.format[OutgoingImage]
  implicit val outgoingEntryImageFormat: Format[OutgoingTransactionImage] = Json.format[OutgoingTransactionImage]

  implicit val failedOutgoingEntryFormat: Format[FailedOutgoingTransactionImage] = Json.format[FailedOutgoingTransactionImage]

  implicit val incomingEntryFormat: Format[IncomingTransaction] = Json.format[IncomingTransaction]

  implicit val tagValueFormat: Format[TagValue] = Json.format[TagValue]
  implicit val anonymizationKeyFormat: Format[AnonymizationKey] = Json.format[AnonymizationKey]

  implicit val entityTagValueFormat: Format[ImageTagValues] = Json.format[ImageTagValues]

  implicit val roleFormat: Format[UserRole] = enumFormat(UserRole.withName)

  implicit val clearTextUserFormat: Format[ClearTextUser] = Json.format[ClearTextUser]
  implicit val apiUserFormat: Format[ApiUser] = Json.format[ApiUser]
  implicit val userPassFormat: Format[UserPass] = Json.format[UserPass]
  implicit val userInfoFormat: Format[UserInfo] = Json.format[UserInfo]

  implicit val patientNameFormat: Format[PatientName] = Json.format[PatientName]
  implicit val patientIdFormat: Format[PatientID] = Json.format[PatientID]
  implicit val patientBirthDateFormat: Format[PatientBirthDate] = Json.format[PatientBirthDate]
  implicit val patientSexFormat: Format[PatientSex] = Json.format[PatientSex]

  implicit val patientFormat: Format[Patient] = Json.format[Patient]

  implicit val studyInstanceUidFormat: Format[StudyInstanceUID] = Json.format[StudyInstanceUID]
  implicit val studyDescriptionFormat: Format[StudyDescription] = Json.format[StudyDescription]
  implicit val studyDateFormat: Format[StudyDate] = Json.format[StudyDate]
  implicit val studyIdFormat: Format[StudyID] = Json.format[StudyID]
  implicit val accessionNumberFormat: Format[AccessionNumber] = Json.format[AccessionNumber]
  implicit val patientAgeFormat: Format[PatientAge] = Json.format[PatientAge]

  implicit val studyFormat: Format[Study] = Json.format[Study]

  implicit val manufacturerFormat: Format[Manufacturer] = Json.format[Manufacturer]
  implicit val stationNameFormat: Format[StationName] = Json.format[StationName]

  implicit val frameOfReferenceUidFormat: Format[FrameOfReferenceUID] = Json.format[FrameOfReferenceUID]

  implicit val seriesInstanceUidFormat: Format[SeriesInstanceUID] = Json.format[SeriesInstanceUID]
  implicit val seriesDescriptionFormat: Format[SeriesDescription] = Json.format[SeriesDescription]
  implicit val seriesDateFormat: Format[SeriesDate] = Json.format[SeriesDate]
  implicit val modalityFormat: Format[Modality] = Json.format[Modality]
  implicit val protocolNameFormat: Format[ProtocolName] = Json.format[ProtocolName]
  implicit val bodyPartExaminedFormat: Format[BodyPartExamined] = Json.format[BodyPartExamined]

  implicit val sourceFormat: Format[Source] = Json.format[Source]
  implicit val sourceRefFormat: Format[SourceRef] = Json.format[SourceRef]

  implicit val destinationFormat: Format[Destination] = Json.format[Destination]

  implicit val seriesFormat: Format[Series] = Json.format[Series]
  implicit val flatSeriesFormat: Format[FlatSeries] = Json.format[FlatSeries]

  implicit val sopInstanceUidFormat: Format[SOPInstanceUID] = Json.format[SOPInstanceUID]
  implicit val imageTypeFormat: Format[ImageType] = Json.format[ImageType]
  implicit val instanceNumberFormat: Format[InstanceNumber] = Json.format[InstanceNumber]

  implicit val imageFormat: Format[Image] = Json.format[Image]

  implicit val exportSetFormat: Format[ExportSetId] = Json.format[ExportSetId]
  implicit val imageAttributeFormat: Format[ImageAttribute] = Json.format[ImageAttribute]

  implicit val imagesFormat: Format[Images] = Json.format[Images]

  implicit val numberOfImageFramesFormat: Format[ImageInformation] = Json.format[ImageInformation]

  implicit val logEntryTypeFormat: Format[LogEntryType] = enumFormat(LogEntryType.withName)

  implicit val logEntryFormat: Format[LogEntry] = Json.format[LogEntry]

  implicit val queryOperatorFormat: Format[QueryOperator] = enumFormat(QueryOperator.withName)

  implicit val queryOrderFormat: Format[QueryOrder] = Json.format[QueryOrder]
  implicit val queryPropertyFormat: Format[QueryProperty] = Json.format[QueryProperty]
  implicit val queryFiltersFormat: Format[QueryFilters] = Json.format[QueryFilters]
  implicit val queryFormat: Format[Query] = Json.format[Query]
  implicit val idsQueryFormat: Format[IdsQuery] = Json.format[IdsQuery]
  implicit val anonymizationKeyQueryFormat: Format[AnonymizationKeyQuery] = Json.format[AnonymizationKeyQuery]

  implicit val seriesTypeFormat: Format[SeriesType] = Json.format[SeriesType]

  implicit val seriesTagFormat: Format[SeriesTag] = Json.format[SeriesTag]

  implicit val seriesIdSeriesTypes: Format[SeriesIdSeriesType] = Json.format[SeriesIdSeriesType]

  implicit val seriesTypeRuleFormat: Format[SeriesTypeRule] = Json.format[SeriesTypeRule]

  implicit val seriesTypeRuleAttributeFormat: Format[SeriesTypeRuleAttribute] = Json.format[SeriesTypeRuleAttribute]

  implicit val forwardingRuleFormat: Format[ForwardingRule] = Json.format[ForwardingRule]

  implicit val importSessionFormat: Format[ImportSession] = Json.format[ImportSession]

  implicit val queryResultSeriesType: Format[SeriesIdSeriesTypesResult] = Json.format[SeriesIdSeriesTypesResult]

  implicit val tagFilterTypeFormat: Format[TagFilterType] = enumFormat(TagFilterType.withName)

  implicit val tagFilterSpecFormat: Format[TagFilterSpec] = Json.format[TagFilterSpec]

  implicit val tagSourceTagFilter: Format[SourceTagFilter] = Json.format[SourceTagFilter]

  implicit val dicomDictionaryKeywordFormat: Format[DicomDictionaryKeyword] = Json.format[DicomDictionaryKeyword]

  implicit val dicomDictionaryKeywordsFormat: Format[DicomDictionaryKeywords] = Json.format[DicomDictionaryKeywords]

  implicit val dicomDictionaryTagFormat: Format[DicomDictionaryTag] = Json.format[DicomDictionaryTag]

  implicit val dicomValueRepresentationFormat: Format[DicomValueRepresentation] = Json.format[DicomValueRepresentation]

  implicit val dicomMultiplicityFormat: Format[Multiplicity] = Json.format[Multiplicity]
}
