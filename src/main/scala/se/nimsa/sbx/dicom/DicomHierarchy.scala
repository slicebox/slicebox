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

package se.nimsa.sbx.dicom

object DicomHierarchy {
  import DicomPropertyValue._
  import se.nimsa.sbx.model.Entity

  sealed trait DicomHierarchyLevel {
    def level: Int
    def >(other: DicomHierarchyLevel): Boolean = level > other.level
    def <(other: DicomHierarchyLevel): Boolean = level < other.level
    override def toString: String = this match {
      case DicomHierarchyLevel.PATIENT => "patient"
      case DicomHierarchyLevel.STUDY => "study"
      case DicomHierarchyLevel.SERIES => "series"
      case DicomHierarchyLevel.IMAGE => "image"
    }
  }

  object DicomHierarchyLevel {

    case object PATIENT extends DicomHierarchyLevel {
      override def level: Int = 1
    }

    case object STUDY extends DicomHierarchyLevel {
      override def level: Int = 2
    }

    case object SERIES extends DicomHierarchyLevel {
      override def level: Int = 3
    }

    case object IMAGE extends DicomHierarchyLevel {
      override def level: Int = 4
    }

    def withName(string: String): DicomHierarchyLevel = string.toLowerCase match {
      case "patient" => PATIENT
      case "study" => STUDY
      case "series" => SERIES
      case "image" => IMAGE
    }

    def withLevel(level: Int): DicomHierarchyLevel = level match {
      case 1 => PATIENT
      case 2 => STUDY
      case 3 => SERIES
      case 4 => IMAGE
    }
  }

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
