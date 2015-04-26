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

package se.nimsa.sbx.dicom

trait DicomPropertyValue { 
  def property: DicomProperty
  def value: String 
}

object DicomPropertyValue {

  case class PatientName(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientName }
  case class PatientID(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientID }
  case class PatientBirthDate(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientBirthDate }
  case class PatientSex(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientSex }

  case class StudyInstanceUID(value: String) extends DicomPropertyValue { def property = DicomProperty.StudyInstanceUID }
  case class StudyDescription(value: String) extends DicomPropertyValue { def property = DicomProperty.StudyDescription }
  case class StudyDate(value: String) extends DicomPropertyValue { def property = DicomProperty.StudyDate }
  case class StudyID(value: String) extends DicomPropertyValue { def property = DicomProperty.StudyID }
  case class AccessionNumber(value: String) extends DicomPropertyValue { def property = DicomProperty.AccessionNumber }
  case class PatientAge(value: String) extends DicomPropertyValue { def property = DicomProperty.PatientAge }
  
  case class SeriesInstanceUID(value: String) extends DicomPropertyValue { def property = DicomProperty.SeriesInstanceUID }
  case class SeriesNumber(value: String) extends DicomPropertyValue { def property = DicomProperty.SeriesNumber }
  case class SeriesDescription(value: String) extends DicomPropertyValue { def property = DicomProperty.SeriesDescription }
  case class SeriesDate(value: String) extends DicomPropertyValue { def property = DicomProperty.SeriesDate }
  case class Modality(value: String) extends DicomPropertyValue { def property = DicomProperty.Modality }
  case class ProtocolName(value: String) extends DicomPropertyValue { def property = DicomProperty.ProtocolName }
  case class BodyPartExamined(value: String) extends DicomPropertyValue { def property = DicomProperty.BodyPartExamined }

  case class SOPInstanceUID(value: String) extends DicomPropertyValue { def property = DicomProperty.SOPInstanceUID }
  case class ImageType(value: String) extends DicomPropertyValue { def property = DicomProperty.ImageType }
  case class InstanceNumber(value: String) extends DicomPropertyValue { def property = DicomProperty.InstanceNumber }

  case class Manufacturer(value: String) extends DicomPropertyValue { def property = DicomProperty.Manufacturer }
  case class StationName(value: String) extends DicomPropertyValue { def property = DicomProperty.StationName }

  case class FrameOfReferenceUID(value: String) extends DicomPropertyValue { def property = DicomProperty.FrameOfReferenceUID }
  
}
