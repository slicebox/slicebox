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

package se.nimsa.sbx.dicom

import org.dcm4che3.data.Tag

case class DicomProperty(val name: String, val dicomTag: Int)

object DicomProperty {
  object PatientName extends DicomProperty("PatientName", Tag.PatientName) 
  object PatientID extends DicomProperty("PatientID", Tag.PatientID) 
  object PatientBirthDate extends DicomProperty("PatientBirthDate", Tag.PatientBirthDate) 
  object PatientSex extends DicomProperty("PatientSex", Tag.PatientSex) 

  object StudyInstanceUID extends DicomProperty("StudyInstanceUID", Tag.StudyInstanceUID) 
  object StudyDescription extends DicomProperty("StudyDescription", Tag.StudyDescription) 
  object StudyDate extends DicomProperty("StudyDate", Tag.StudyDate) 
  object StudyID extends DicomProperty("StudyID", Tag.StudyID) 
  object AccessionNumber extends DicomProperty("AccessionNumber", Tag.AccessionNumber) 
  object PatientAge extends DicomProperty("PatientAge", Tag.PatientAge)
  
  object SeriesInstanceUID extends DicomProperty("SeriesInstanceUID", Tag.SeriesInstanceUID) 
  object SeriesNumber extends DicomProperty("SeriesNumber", Tag.SeriesNumber) 
  object SeriesDescription extends DicomProperty("SeriesDescription", Tag.SeriesDescription) 
  object SeriesDate extends DicomProperty("SeriesDate", Tag.SeriesDate) 
  object Modality extends DicomProperty("Modality", Tag.Modality) 
  object ProtocolName extends DicomProperty("ProtocolName", Tag.ProtocolName) 
  object BodyPartExamined extends DicomProperty("BodyPartExamined", Tag.BodyPartExamined) 

  object SOPInstanceUID extends DicomProperty("SOPInstanceUID", Tag.SOPInstanceUID) 
  object ImageType extends DicomProperty("ImageType", Tag.ImageType) 
  object InstanceNumber extends DicomProperty("InstanceNumber", Tag.InstanceNumber) 

  object Manufacturer extends DicomProperty("Manufacturer", Tag.Manufacturer) 
  object StationName extends DicomProperty("StationName", Tag.StationName) 

  object FrameOfReferenceUID extends DicomProperty("FrameOfReferenceUID", Tag.FrameOfReferenceUID) 
}
