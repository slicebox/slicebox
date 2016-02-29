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

package se.nimsa.sbx.dicom

object DicomHierarchy {
  import DicomPropertyValue._
  import se.nimsa.sbx.model.Entity

  case class Patient(
      id: Long,
      patientName: PatientName,
      patientID: PatientID,
      patientBirthDate: PatientBirthDate,
      patientSex: PatientSex) extends Entity
      
  case class Study(
      id: Long,
      patientId: Long,
      studyInstanceUID: StudyInstanceUID,
      studyDescription: StudyDescription,
      studyDate: StudyDate,
      studyID: StudyID,
      accessionNumber: AccessionNumber,
      patientAge: PatientAge) extends Entity
      
  case class Series(
      id: Long,
      studyId: Long,
      seriesInstanceUID: SeriesInstanceUID,
      seriesDescription: SeriesDescription,
      seriesDate: SeriesDate,
      modality: Modality,
      protocolName: ProtocolName,
      bodyPartExamined: BodyPartExamined,
      manufacturer: Manufacturer,
      stationName: StationName,
      frameOfReferenceUID: FrameOfReferenceUID) extends Entity

  case class Image(
      id: Long,
      seriesId: Long,
      sopInstanceUID: SOPInstanceUID,
      imageType: ImageType,
      instanceNumber: InstanceNumber) extends Entity

  case class FlatSeries(
      id: Long,
      patient: Patient,
      study: Study,
      series: Series) extends Entity

}
