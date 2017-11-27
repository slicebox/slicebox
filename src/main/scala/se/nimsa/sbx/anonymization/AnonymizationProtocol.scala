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

import se.nimsa.sbx.metadata.MetaDataProtocol.{QueryOrder, QueryProperty}
import se.nimsa.sbx.model.Entity

object AnonymizationProtocol {

  case class AnonymizationKeyQuery(
    startIndex: Long,
    count: Long,
    order: Option[QueryOrder],
    queryProperties: Seq[QueryProperty])
    
  case class ImageTagValues(imageId: Long, tagValues: Seq[TagValue])  
      
  case class TagValue(tag: Int, value: String)

  case class AnonymizationKey(
    id: Long,
    created: Long,
    patientName: String,
    anonPatientName: String,
    patientID: String,
    anonPatientID: String,
    patientBirthDate: String,
    studyInstanceUID: String,
    anonStudyInstanceUID: String,
    studyDescription: String,
    studyID: String,
    accessionNumber: String,
    seriesInstanceUID: String,
    anonSeriesInstanceUID: String,
    seriesDescription: String,
    protocolName: String,
    frameOfReferenceUID: String,
    anonFrameOfReferenceUID: String) extends Entity

  case class AnonymizationKeyImage(
      id: Long,
      anonymizationKeyId: Long,
      imageId: Long) extends Entity

  trait AnonymizationRequest

  case class GetOrCreateAnonymizationKey(patientName: Option[String], patientId: Option[String],
                                         patientSex: Option[String], patientBirthDate: Option[String],
                                         patientAge: Option[String], studyInstanceUID: Option[String],
                                         studyDescription: Option[String], studyID: Option[String],
                                         accessionNumber: Option[String], seriesInstanceUID: Option[String],
                                         seriesDescription: Option[String], protocolName: Option[String],
                                         frameOfReferenceUID: Option[String], tagValues: Seq[TagValue]) extends AnonymizationRequest

  case class GetAnonymizationKeysForPatient(patientName: String, patientId: String) extends AnonymizationRequest

  case class GetReverseAnonymizationKeysForPatient(anonPatientName: String, anonPatientID: String) extends AnonymizationRequest

  case class GetAnonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]) extends AnonymizationRequest

  case class GetAnonymizationKey(anonymizationKeyId: Long) extends AnonymizationRequest
  
  case class GetImageIdsForAnonymizationKey(anonymizationKeyId: Long) extends AnonymizationRequest
  
  case class QueryAnonymizationKeys(query: AnonymizationKeyQuery) extends AnonymizationRequest
  
  case class RemoveAnonymizationKey(anonymizationKeyId: Long) extends AnonymizationRequest

  case class AddAnonymizationKey(anonymizationKey: AnonymizationKey) extends AnonymizationRequest

  case class AnonymizationKeyAdded(anonymizationKey: AnonymizationKey)

  case class AnonymizationKeyRemoved(anonymizationKeyId: Long)

  case class AnonymizationKeys(anonymizationKeys: Seq[AnonymizationKey])
}
