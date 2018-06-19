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

import java.io.{ByteArrayInputStream, DataInputStream}
import java.time.LocalDate

import akka.util.ByteString
import se.nimsa.dicom.data.Elements._
import se.nimsa.dicom.data._
import se.nimsa.sbx.dicom.DicomHierarchy.{Patient, Study}

/**
  * Scala and dicom-streams port of the Jpg2Dcm tool which is part of the dcm4che toolkit.
  * See https://github.com/dcm4che/dcm4che/, specifically
  * https://github.com/dcm4che/dcm4che/blob/master/dcm4che-tool/dcm4che-tool-jpg2dcm/src/main/java/org/dcm4che3/tool/jpg2dcm/Jpg2Dcm.java
  */
object Jpeg2Dcm {

  private val FF = 0xff

  private val SOF = 0xc0

  private val DHT = 0xc4

  private val DAC = 0xcc

  private val SOI = 0xd8

  private val SOS = 0xda

  private val charset = "ISO_IR 100"

  private val transferSyntax = UID.JPEGBaselineProcess1

  def apply(bytes: ByteString, patient: Patient, study: Study, optionalDescription: Option[String]): ByteString = {

    val jpgInput = new DataInputStream(new ByteArrayInputStream(bytes.toArray))

    var attrs = Elements.empty()
      .setString(Tag.SOPClassUID, UID.SecondaryCaptureImageStorage)
      .setString(Tag.PatientName, patient.patientName.value)
      .setString(Tag.PatientID, patient.patientID.value)
      .setString(Tag.PatientSex, patient.patientSex.value)
      .setString(Tag.PatientBirthDate, patient.patientBirthDate.value)
      .setString(Tag.AccessionNumber, study.accessionNumber.value)
      .setString(Tag.PatientAge, study.patientAge.value)
      .setString(Tag.StudyDate, study.studyDate.value)
      .setString(Tag.StudyDescription, study.studyDescription.value)
      .setString(Tag.StudyID, study.studyID.value)
      .setString(Tag.StudyInstanceUID, study.studyInstanceUID.value)
      .setString(Tag.SpecificCharacterSet, charset)
      .setString(Tag.SeriesDescription, optionalDescription.getOrElse(""))

    val buffer = new Array[Byte](8192)
    val jpgLen = bytes.length
    val (out, jpgHeaderLen) = readHeader(attrs, jpgInput, buffer)
    attrs = out
    attrs = ensureUS(attrs, Tag.BitsAllocated, 8)
    attrs = ensureUS(attrs, Tag.BitsStored, attrs.getInt(Tag.BitsAllocated).getOrElse(if ((buffer(jpgHeaderLen) & 0xff) > 8) 16 else 8))
    attrs = ensureUS(attrs, Tag.HighBit, attrs.getInt(Tag.BitsStored).getOrElse(buffer(jpgHeaderLen) & 0xff - 1))
    attrs = ensureUS(attrs, Tag.PixelRepresentation, 0)
    attrs = ensureUID(attrs, Tag.StudyInstanceUID)
    attrs = ensureUID(attrs, Tag.SeriesInstanceUID)
    attrs = ensureUID(attrs, Tag.SOPInstanceUID)
    val now = LocalDate.now()
    attrs = attrs.setDate(Tag.InstanceCreationDate, now)
      .setDateTime(Tag.InstanceCreationTime, now.atStartOfDay(systemZone))
      .setDate(Tag.SeriesDate, now)

    val fmiElements = Elements.fileMetaInformationElements(attrs.getString(Tag.SOPInstanceUID).get, attrs.getString(Tag.SOPClassUID).get, transferSyntax)
    attrs = fmiElements.foldLeft(attrs)((a, e) => a.set(e))

    attrs = attrs.sorted()

    def createFragment(): FragmentElement = {
      val header = ByteString(buffer.take(jpgHeaderLen))
      var body = ByteString.empty
      var r = jpgInput.read(buffer)
      while (r > 0) {
        body = body ++ buffer.take(r)
        r = jpgInput.read(buffer)
      }
      if ((jpgLen & 1) != 0)
        body ++ ByteString(0)
      FragmentElement(2, (jpgLen + 1) & ~1, Value(header ++ body), bigEndian = false)
    }

    attrs.toBytes ++
      FragmentsElement(Tag.PixelData, VR.OB).toBytes ++
      FragmentElement(1, 0, Value.empty, bigEndian = false).toBytes ++
      createFragment().toBytes ++
      SequenceDelimitationElement(bigEndian = false).toBytes
  }

  private def readHeader(elements: Elements, jpgInput: DataInputStream, initialBuffer: Array[Byte]): (Elements, Int) = {
    var out = elements
    if (jpgInput.read() != FF || jpgInput.read() != SOI
      || jpgInput.read() != FF)
      throw new IllegalArgumentException("JPEG stream does not start with FF D8 FF")
    var marker = jpgInput.read()
    var segmLen = 0
    var seenSOF = false
    var buffer = initialBuffer
    buffer(0) = FF.toByte
    buffer(1) = SOI.toByte
    buffer(2) = FF.toByte
    buffer(3) = marker.toByte
    var jpgHeaderLen = 4
    while (marker != SOS) {
      segmLen = jpgInput.readUnsignedShort()
      if (buffer.length < jpgHeaderLen + segmLen + 2)
        buffer = growBuffer(buffer, jpgHeaderLen + segmLen + 2, jpgHeaderLen)
      buffer(jpgHeaderLen) = (segmLen >>> 8).toByte
      jpgHeaderLen += 1
      buffer(jpgHeaderLen) = segmLen.toByte
      jpgHeaderLen += 1
      jpgInput.readFully(buffer, jpgHeaderLen, segmLen - 2)
      if ((marker & 0xf0) == SOF && marker != DHT && marker != DAC) {
        seenSOF = true
        val p = buffer(jpgHeaderLen) & 0xff
        val y = ((buffer(jpgHeaderLen + 1) & 0xff) << 8) | (buffer(jpgHeaderLen + 2) & 0xff)
        val x = ((buffer(jpgHeaderLen + 3) & 0xff) << 8) | (buffer(jpgHeaderLen + 4) & 0xff)
        val nf = buffer(jpgHeaderLen + 5) & 0xff
        out = out.setInt(Tag.SamplesPerPixel, nf)
        if (nf == 3)
          out = out
            .setString(Tag.PhotometricInterpretation, "YBR_FULL_422")
            .setInt(Tag.PlanarConfiguration, 0)
        else
          out = out
            .setString(Tag.PhotometricInterpretation, "MONOCHROME2")

        out = out
          .setInt(Tag.Rows, y)
          .setInt(Tag.Columns, x)
          .setInt(Tag.BitsAllocated, if (p > 8) 16 else 8)
          .setInt(Tag.BitsStored, p)
          .setInt(Tag.HighBit, p - 1)
          .setInt(Tag.PixelRepresentation, 0)
      }
      jpgHeaderLen += segmLen - 2
      if (jpgInput.read() != FF)
        throw new IllegalArgumentException("Missing SOS segment in JPEG stream")
      marker = jpgInput.read()
      buffer(jpgHeaderLen) = FF.toByte
      jpgHeaderLen += 1
      buffer(jpgHeaderLen) = marker.toByte
      jpgHeaderLen += 1
    }
    if (!seenSOF)
      throw new IllegalArgumentException("Missing SOF segment in JPEG stream")
    (out, jpgHeaderLen)
  }

  private def growBuffer(buffer: Array[Byte], minSize: Int, jpgHeaderLen: Int): Array[Byte] = {
    var newSize = buffer.length << 1
    while (newSize < minSize) newSize <<= 1
    val tmp = new Array[Byte](newSize)
    System.arraycopy(buffer, 0, tmp, 0, jpgHeaderLen)
    tmp
  }

  private def ensureUID(elements: Elements, tag: Int): Elements =
    if (elements.hasElement(tag)) elements else elements.setString(tag, createUID())

  private def ensureUS(elements: Elements, tag: Int, value: Int): Elements =
    if (elements.hasElement(tag)) elements else elements.setInt(tag, value)

}
