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

import akka.util.ByteString
import org.dcm4che3.data.{Attributes, Keyword}
import se.nimsa.dicom.VR.VR
import se.nimsa.dicom.{Tag, groupNumber, padToEvenLength}
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._

object DicomUtil {

  def isAnonymous(attributes: Attributes): Boolean = attributes.getString(Tag.PatientIdentityRemoved, "NO") == "YES"

  def cloneAttributes(attributes: Attributes): Attributes = new Attributes(attributes)

  def attributesToPatient(attributes: Attributes): Patient =
    Patient(
      -1,
      PatientName(valueOrEmpty(attributes, DicomProperty.PatientName.dicomTag)),
      PatientID(valueOrEmpty(attributes, DicomProperty.PatientID.dicomTag)),
      PatientBirthDate(valueOrEmpty(attributes, DicomProperty.PatientBirthDate.dicomTag)),
      PatientSex(valueOrEmpty(attributes, DicomProperty.PatientSex.dicomTag)))

  def attributesToStudy(attributes: Attributes): Study =
    Study(
      -1,
      -1,
      StudyInstanceUID(valueOrEmpty(attributes, DicomProperty.StudyInstanceUID.dicomTag)),
      StudyDescription(valueOrEmpty(attributes, DicomProperty.StudyDescription.dicomTag)),
      StudyDate(valueOrEmpty(attributes, DicomProperty.StudyDate.dicomTag)),
      StudyID(valueOrEmpty(attributes, DicomProperty.StudyID.dicomTag)),
      AccessionNumber(valueOrEmpty(attributes, DicomProperty.AccessionNumber.dicomTag)),
      PatientAge(valueOrEmpty(attributes, DicomProperty.PatientAge.dicomTag)))

  def attributesToSeries(attributes: Attributes): Series =
    Series(
      -1,
      -1,
      SeriesInstanceUID(valueOrEmpty(attributes, DicomProperty.SeriesInstanceUID.dicomTag)),
      SeriesDescription(valueOrEmpty(attributes, DicomProperty.SeriesDescription.dicomTag)),
      SeriesDate(valueOrEmpty(attributes, DicomProperty.SeriesDate.dicomTag)),
      Modality(valueOrEmpty(attributes, DicomProperty.Modality.dicomTag)),
      ProtocolName(valueOrEmpty(attributes, DicomProperty.ProtocolName.dicomTag)),
      BodyPartExamined(valueOrEmpty(attributes, DicomProperty.BodyPartExamined.dicomTag)),
      Manufacturer(valueOrEmpty(attributes, DicomProperty.Manufacturer.dicomTag)),
      StationName(valueOrEmpty(attributes, DicomProperty.StationName.dicomTag)),
      FrameOfReferenceUID(valueOrEmpty(attributes, DicomProperty.FrameOfReferenceUID.dicomTag)))

  def attributesToImage(attributes: Attributes): Image =
    Image(
      -1,
      -1,
      SOPInstanceUID(valueOrEmpty(attributes, DicomProperty.SOPInstanceUID.dicomTag)),
      ImageType(readMultiple(attributes.getStrings(DicomProperty.ImageType.dicomTag))),
      InstanceNumber(valueOrEmpty(attributes, DicomProperty.InstanceNumber.dicomTag)))

  private def valueOrEmpty(attributes: Attributes, tag: Int) = Option(attributes.getString(tag)).getOrElse("")

  def readMultiple(values: Array[String]): String =
    if (values == null || values.length == 0)
      ""
    else
      values.tail.foldLeft(values.head)((result, part) => result + "/" + part)

  def getStrings(attrs: Attributes, tag: Int): Array[String] = {
    val s = attrs.getStrings(tag)
    if (s == null || s.isEmpty) Array("") else s
  }

  def concatenatedStringForTag(attrs: Attributes, tag: Int): String = {
    val array = getStrings(attrs, tag)
    array.mkString(",")
  }

  def nameForTag(tag: Int): String = {
    val name = Keyword.valueOf(tag)
    if (name == null) "" else name
  }

  def toAsciiBytes(s: String, vr: VR): ByteString = padToEvenLength(ByteString(s), vr)

  def isOverlay(tag: Int): Boolean = {
    val group = groupNumber(tag)
    group >= 0x6000 && group < 0x6100
  }

}
