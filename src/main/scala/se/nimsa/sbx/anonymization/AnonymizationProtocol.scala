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

package se.nimsa.sbx.anonymization

import se.nimsa.dicom.data.TagPath.TagPathTag
import se.nimsa.sbx.dicom.DicomHierarchy.DicomHierarchyLevel
import se.nimsa.sbx.metadata.MetaDataProtocol.{QueryOrder, QueryProperty}
import se.nimsa.sbx.model.Entity

object AnonymizationProtocol {

  case class AnonymizationKeyQuery(
    startIndex: Long,
    count: Long,
    order: Option[QueryOrder],
    queryProperties: Seq[QueryProperty])

  case class ImageTagValues(imageId: Long, tagValues: Seq[TagValue])

  case class TagValue(tagPath: TagPathTag, value: String)
  case class TagValueAnonymized(tagPath: TagPathTag, value: String, anonymizedValue: String)

  case class AnonymizationKey(
    id: Long,
    created: Long,
    imageId: Long,
    patientName: String,
    anonPatientName: String,
    patientID: String,
    anonPatientID: String,
    studyInstanceUID: String,
    anonStudyInstanceUID: String,
    seriesInstanceUID: String,
    anonSeriesInstanceUID: String,
    sopInstanceUID: String,
    anonSOPInstanceUID: String) extends Entity

  case class AnonymizationKeyValue(
    anonymizationKeyId: Long,
    tagPath: String,
    value: String,
    anonymizedValue: String)

  case class AnonymizationKeyValues(matchLevel: DicomHierarchyLevel, anonymizationKeyMaybe: Option[AnonymizationKey], values: Seq[TagValueAnonymized]) {
    def isEmpty: Boolean = anonymizationKeyMaybe.isEmpty
  }

  object AnonymizationKeyValues {
    def empty: AnonymizationKeyValues = AnonymizationKeyValues(DicomHierarchyLevel.PATIENT, None, Seq.empty)
  }

  trait AnonymizationRequest

  case class InsertAnonymizationKey(imageId: Long, tagValues: Set[TagValueAnonymized]) extends AnonymizationRequest

  case class GetAnonymizationKeyValues(patientName: String, patientID: String,
                                       studyInstanceUID: String,
                                       seriesInstanceUID: String,
                                       sopInstanceUID: String) extends AnonymizationRequest

  case class GetReverseAnonymizationKeyValues(anonPatientName: String, anonPatientID: String,
                                              anonStudyInstanceUID: String,
                                              anonSeriesInstanceUID: String,
                                              anonSOPInstanceUID: String) extends AnonymizationRequest

  case class GetTagValuesForAnonymizationKey(anonymizationKeyId: Long) extends AnonymizationRequest

  case class GetAnonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]) extends AnonymizationRequest

  case class GetAnonymizationKey(anonymizationKeyId: Long) extends AnonymizationRequest

  case class QueryAnonymizationKeys(query: AnonymizationKeyQuery) extends AnonymizationRequest

  case class RemoveAnonymizationKey(anonymizationKeyId: Long) extends AnonymizationRequest

  case class AddAnonymizationKey(anonymizationKey: AnonymizationKey) extends AnonymizationRequest

  case class AnonymizationKeyAdded(anonymizationKey: AnonymizationKey)

  case class AnonymizationKeyRemoved(anonymizationKeyId: Long)

  case class AnonymizationKeys(anonymizationKeys: Seq[AnonymizationKey])
}
