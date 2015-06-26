/*
 * Copyright 2015 Lars Edenbrandt
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

import se.nimsa.sbx.model.Entity
import org.dcm4che3.data.Attributes

object AnonymizationProtocol {

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
    accessionNumber: String) extends Entity

  trait AnonymizationRequest

  case class ReverseAnonymization(dataset: Attributes) extends AnonymizationRequest

  case class Anonymize(dataset: Attributes, tagValues: Seq[TagValue]) extends AnonymizationRequest

  case class GetAnonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]) extends AnonymizationRequest

  case class RemoveAnonymizationKey(anonymizationKeyId: Long) extends AnonymizationRequest

  case class AnonymizationKeyRemoved(anonymizationKeyId: Long)

  case class AnonymizationKeys(anonymizationKeys: Seq[AnonymizationKey])

}
