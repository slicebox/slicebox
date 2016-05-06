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

import org.dcm4che3.data.Tag

case class DicomProperty(name: String, dicomTag: Int)

object DicomProperty {
  object PatientName extends DicomProperty("patientName", Tag.PatientName) 
  object PatientID extends DicomProperty("patientID", Tag.PatientID) 
  object PatientBirthDate extends DicomProperty("patientBirthDate", Tag.PatientBirthDate) 
  object PatientSex extends DicomProperty("patientSex", Tag.PatientSex) 

  object StudyInstanceUID extends DicomProperty("studyInstanceUID", Tag.StudyInstanceUID) 
  object StudyDescription extends DicomProperty("studyDescription", Tag.StudyDescription) 
  object StudyDate extends DicomProperty("studyDate", Tag.StudyDate) 
  object StudyID extends DicomProperty("studyID", Tag.StudyID) 
  object AccessionNumber extends DicomProperty("accessionNumber", Tag.AccessionNumber) 
  object PatientAge extends DicomProperty("patientAge", Tag.PatientAge)
  
  object SeriesInstanceUID extends DicomProperty("seriesInstanceUID", Tag.SeriesInstanceUID) 
  object SeriesNumber extends DicomProperty("seriesNumber", Tag.SeriesNumber) 
  object SeriesDescription extends DicomProperty("seriesDescription", Tag.SeriesDescription) 
  object SeriesDate extends DicomProperty("seriesDate", Tag.SeriesDate) 
  object Modality extends DicomProperty("modality", Tag.Modality) 
  object ProtocolName extends DicomProperty("protocolName", Tag.ProtocolName) 
  object BodyPartExamined extends DicomProperty("bodyPartExamined", Tag.BodyPartExamined) 

  object SOPInstanceUID extends DicomProperty("sopInstanceUID", Tag.SOPInstanceUID) 
  object ImageType extends DicomProperty("imageType", Tag.ImageType) 
  object InstanceNumber extends DicomProperty("instanceNumber", Tag.InstanceNumber) 

  object Manufacturer extends DicomProperty("manufacturer", Tag.Manufacturer) 
  object StationName extends DicomProperty("stationName", Tag.StationName) 

  object FrameOfReferenceUID extends DicomProperty("frameOfReferenceUID", Tag.FrameOfReferenceUID) 
}
