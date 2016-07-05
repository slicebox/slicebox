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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream}
import java.util.Date

import org.dcm4che3.data.{Attributes, Tag, UID, VR}
import org.dcm4che3.io.DicomOutputStream
import org.dcm4che3.util.UIDUtils
import se.nimsa.sbx.dicom.DicomHierarchy.{Patient, Study}

/**
 * Scala port and minor adaptation of the Jpg2Dcm tool which is part of the Dcm4Che toolkit.
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

  private val transferSyntax = UID.JPEGBaseline1

  def apply(bytes: Array[Byte], patient: Patient, study: Study, optionalDescription: Option[String]): DicomData = {

    val jpgInput = new DataInputStream(new ByteArrayInputStream(bytes))

    try {

      val attrs = new Attributes()
      attrs.setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
      attrs.setString(Tag.PatientName, VR.PN, patient.patientName.value)
      attrs.setString(Tag.PatientID, VR.LO, patient.patientID.value)
      attrs.setString(Tag.PatientSex, VR.CS, patient.patientSex.value)
      attrs.setString(Tag.PatientBirthDate, VR.DA, patient.patientBirthDate.value)
      attrs.setString(Tag.AccessionNumber, VR.SH, study.accessionNumber.value)
      attrs.setString(Tag.PatientAge, VR.AS, study.patientAge.value)
      attrs.setString(Tag.StudyDate, VR.DA, study.studyDate.value)
      attrs.setString(Tag.StudyDescription, VR.LO, study.studyDescription.value)
      attrs.setString(Tag.StudyID, VR.LO, study.studyID.value)
      attrs.setString(Tag.StudyInstanceUID, VR.UI, study.studyInstanceUID.value)
      attrs.setString(Tag.SpecificCharacterSet, VR.CS, charset)

      // add series description if it exists
      optionalDescription.foreach(description => attrs.setString(Tag.SeriesDescription, VR.LO, description))

      val buffer = new Array[Byte](8192)
      val jpgLen = bytes.length
      val jpgHeaderLen = readHeader(attrs, jpgInput, buffer)
      ensureUS(attrs, Tag.BitsAllocated, 8)
      ensureUS(attrs, Tag.BitsStored, attrs.getInt(Tag.BitsAllocated, if ((buffer(jpgHeaderLen) & 0xff) > 8) 16 else 8))
      ensureUS(attrs, Tag.HighBit, attrs.getInt(Tag.BitsStored, buffer(jpgHeaderLen) & 0xff) - 1)
      ensureUS(attrs, Tag.PixelRepresentation, 0)
      ensureUID(attrs, Tag.StudyInstanceUID)
      ensureUID(attrs, Tag.SeriesInstanceUID)
      ensureUID(attrs, Tag.SOPInstanceUID)
      val now = new Date()
      attrs.setDate(Tag.InstanceCreationDate, VR.DA, now)
      attrs.setDate(Tag.InstanceCreationTime, VR.TM, now)
      attrs.setDate(Tag.SeriesDate, VR.DA, now)
      val fmi = attrs.createFileMetaInformation(transferSyntax)
      val baos = new ByteArrayOutputStream()
      val dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)
      try {
        dos.writeDataset(fmi, attrs)
        dos.writeHeader(Tag.PixelData, VR.OB, -1)
        dos.writeHeader(Tag.Item, null, 0)
        dos.writeHeader(Tag.Item, null, (jpgLen + 1) & ~1)
        dos.write(buffer, 0, jpgHeaderLen)
        var r = jpgInput.read(buffer)
        while (r > 0) {
          dos.write(buffer, 0, r)
          r = jpgInput.read(buffer)
        }
        if ((jpgLen & 1) != 0)
          dos.write(0)
        dos.writeHeader(Tag.SequenceDelimitationItem, null, 0)
        DicomUtil.loadDicomData(baos.toByteArray, withPixelData = true)
      } finally {
        dos.close()
      }
    } finally {
      jpgInput.close()
    }
  }

  private def readHeader(attrs: Attributes, jpgInput: DataInputStream, initialBuffer: Array[Byte]): Int = {
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
        attrs.setInt(Tag.SamplesPerPixel, VR.US, nf)
        if (nf == 3) {
          attrs.setString(Tag.PhotometricInterpretation, VR.CS, "YBR_FULL_422")
          attrs.setInt(Tag.PlanarConfiguration, VR.US, 0)
        } else
          attrs.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
        attrs.setInt(Tag.Rows, VR.US, y)
        attrs.setInt(Tag.Columns, VR.US, x)
        attrs.setInt(Tag.BitsAllocated, VR.US, if (p > 8) 16 else 8)
        attrs.setInt(Tag.BitsStored, VR.US, p)
        attrs.setInt(Tag.HighBit, VR.US, p - 1)
        attrs.setInt(Tag.PixelRepresentation, VR.US, 0)
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
    jpgHeaderLen
  }

  private def growBuffer(buffer: Array[Byte], minSize: Int, jpgHeaderLen: Int): Array[Byte] = {
    var newSize = buffer.length << 1
    while (newSize < minSize) newSize <<= 1
    val tmp = new Array[Byte](newSize)
    System.arraycopy(buffer, 0, tmp, 0, jpgHeaderLen)
    tmp
  }

  private def ensureUID(attrs: Attributes, tag: Int): Unit =
    if (!attrs.containsValue(tag))
      attrs.setString(tag, VR.UI, UIDUtils.createUID())

  private def ensureUS(attrs: Attributes, tag: Int, value: Int): Unit =
    if (!attrs.containsValue(tag))
      attrs.setInt(tag, VR.US, value)

}
