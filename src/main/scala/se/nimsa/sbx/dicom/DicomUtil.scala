/*
 * Copyright 2017 Lars Edenbrandt
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

import java.awt.image.BufferedImage
import java.io._
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

import akka.util.ByteString
import org.dcm4che3.data.Attributes.Visitor
import org.dcm4che3.data.{Attributes, Keyword, Tag, VR}
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam
import org.dcm4che3.io.DicomInputStream.IncludeBulkData
import org.dcm4che3.io.{DicomInputStream, DicomOutputStream}
import org.dcm4che3.util.{SafeClose, TagUtils}
import se.nimsa.sbx.dicom.Contexts.Context
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

object DicomUtil {

  def isAnonymous(attributes: Attributes) = attributes.getString(Tag.PatientIdentityRemoved, "NO") == "YES"

  def cloneAttributes(attributes: Attributes): Attributes = new Attributes(attributes)

  def saveDicomData(dicomData: DicomData, filePath: Path): Unit =
    saveDicomData(dicomData, Files.newOutputStream(filePath))

  def saveDicomData(dicomData: DicomData, outputStream: OutputStream): Unit = {
    var dos: DicomOutputStream = null
    try {
      val transferSyntaxUID = dicomData.metaInformation.getString(Tag.TransferSyntaxUID)
      if (transferSyntaxUID == null || transferSyntaxUID.isEmpty)
        throw new IllegalArgumentException("DICOM meta information is missing transfer syntax UID")

      // the transfer syntax uid given below is for the meta info block. The TS UID in the meta itself is used for the
      // remainder of the file
      dos = new DicomOutputStream(outputStream, TransferSyntaxes.ExplicitVrLittleEndian.uid)

      dos.writeDataset(dicomData.metaInformation, dicomData.attributes)
    } finally {
      SafeClose.close(dos)
    }
  }

  def loadDicomData(path: Path, withPixelData: Boolean): DicomData =
    try
      loadDicomData(new BufferedInputStream(Files.newInputStream(path)), withPixelData)
    catch {
      case NonFatal(_) => null
    }

  def loadDicomData(byteArray: Array[Byte], withPixelData: Boolean): DicomData =
    try
      loadDicomData(new BufferedInputStream(new ByteArrayInputStream(byteArray)), withPixelData)
    catch {
      case NonFatal(_) => null
    }

  def loadDicomData(inputStream: InputStream, withPixelData: Boolean): DicomData = {
    var dis: DicomInputStream = null
    try {
      dis = new DicomInputStream(inputStream)
      val fmi = if (dis.getFileMetaInformation == null) new Attributes() else dis.getFileMetaInformation
      if (fmi.getString(Tag.TransferSyntaxUID) == null || fmi.getString(Tag.TransferSyntaxUID).isEmpty)
        fmi.setString(Tag.TransferSyntaxUID, VR.UI, dis.getTransferSyntax)
      val attributes =
        if (withPixelData) {
          dis.setIncludeBulkData(IncludeBulkData.YES)
          dis.readDataset(-1, -1)
        } else {
          dis.setIncludeBulkData(IncludeBulkData.NO)
          dis.readDataset(-1, Tag.PixelData)
        }

      DicomData(attributes, fmi)
    } catch {
      case NonFatal(_) => null
    } finally {
      SafeClose.close(dis)
    }
  }

  def toByteArray(path: Path): Array[Byte] = toByteArray(loadDicomData(path, withPixelData = true))

  def toByteArray(dicomData: DicomData): Array[Byte] = {
    val bos = new ByteArrayOutputStream
    saveDicomData(dicomData, bos)
    bos.close()
    bos.toByteArray
  }

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

  def checkContext(dicomData: DicomData, contexts: Seq[Context]): Unit = {
    val tsUid = dicomData.metaInformation.getString(Tag.TransferSyntaxUID)
    val mScUid = dicomData.metaInformation.getString(Tag.MediaStorageSOPClassUID)
    val dScUid = dicomData.attributes.getString(Tag.SOPClassUID)
    val scUid = if (mScUid == null || mScUid.isEmpty) dScUid else mScUid
    if (tsUid == null || scUid == null)
      throw new IllegalArgumentException("DICOM attributes must contain meta information (transfer syntax UID and SOP class UID)")
    if (!contexts.exists(context => context.sopClass.uid == scUid && context.transferSyntaxes.map(_.uid).contains(tsUid)))
      throw new IllegalArgumentException(s"The presentation context [SOPClassUID = $scUid, TransferSyntaxUID = $tsUid] is not supported")
  }

  def fileToBufferedImages(path: Path): Seq[BufferedImage] = {
    val iter = ImageIO.getImageReadersByFormatName("DICOM")
    val reader = iter.next()
    val iis = ImageIO.createImageInputStream(path.toFile)
    reader.setInput(iis, false)
    val param = reader.getDefaultReadParam.asInstanceOf[DicomImageReadParam]
    val bufferedImage = reader.read(0, param)
    iis.close()
    Seq(bufferedImage)
  }

  def readImageAttributes(attributes: Attributes): List[ImageAttribute] =
    readImageAttributes(attributes, 0, Nil, Nil)

  def readImageAttributes(attributes: Attributes, depth: Int, tagPath: List[Int], namePath: List[String]): List[ImageAttribute] = {
    val attributesBuffer = ListBuffer.empty[ImageAttribute]
    if (attributes != null) {
      attributes.accept(new Visitor() {
        override def visit(attrs: Attributes, tag: Int, vr: VR, value: AnyRef): Boolean = {
          val length = lengthOf(attrs.getBytes(tag))
          val group = TagUtils.groupNumber(tag)
          val element = TagUtils.elementNumber(tag)
          val name = nameForTag(tag)
          val vrName = vr.name

          val values = vr match {
            case VR.OW | VR.OF | VR.OB =>
              List(s"< Binary data ($length bytes) >")
            case _ =>
              getStrings(attrs, tag).toList
          }

          val multiplicity = values.length

          attributesBuffer += ImageAttribute(
            tag,
            group,
            element,
            name,
            vrName,
            multiplicity,
            length,
            depth,
            tagPath,
            namePath,
            values)
          if (vr == VR.SQ)
            attributesBuffer ++= readImageAttributes(attrs.getNestedDataset(tag), depth + 1, tagPath :+ tag, namePath :+ name)
          true
        }
      }, false)
    }
    attributesBuffer.toList
  }

  private def lengthOf(bytes: Array[Byte]) =
    if (bytes == null)
      0
    else
      bytes.length

  private def getStrings(attrs: Attributes, tag: Int) = {
    val s = attrs.getStrings(tag)
    if (s == null || s.isEmpty) Array("") else s
  }

  def concatenatedStringForTag(attrs: Attributes, tag: Int) = {
    val array = getStrings(attrs, tag)
    array.mkString(",")
  }

  def nameForTag(tag: Int) = {
    val name = Keyword.valueOf(tag)
    if (name == null) "" else name
  }

  def padToEvenLength(bytes: ByteString): ByteString = {
    val padding = if (bytes.length % 2 != 0) ByteString(0) else ByteString.empty
    bytes ++ padding
  }
}
