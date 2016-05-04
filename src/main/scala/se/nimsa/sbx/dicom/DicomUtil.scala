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

import org.dcm4che3.data.Attributes
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import org.dcm4che3.data.Tag
import org.dcm4che3.util.SafeClose
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.io.DicomOutputStream
import java.nio.file.Files
import java.io.BufferedInputStream
import java.nio.file.Path
import org.dcm4che3.data.UID
import org.dcm4che3.io.DicomInputStream.IncludeBulkData
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam
import java.io.ByteArrayOutputStream
import org.dcm4che3.data.Attributes.Visitor
import org.dcm4che3.data.VR
import scala.collection.mutable.ListBuffer
import org.dcm4che3.util.TagUtils
import org.dcm4che3.data.Keyword
import org.dcm4che3.io.BulkDataDescriptor

object DicomUtil {

  val defaultTransferSyntax = UID.ExplicitVRLittleEndian

  def isAnonymous(dataset: Attributes) = dataset.getString(Tag.PatientIdentityRemoved, "NO") == "YES"

  def cloneDataset(dataset: Attributes): Attributes = new Attributes(dataset)

  def saveDataset(dataset: Attributes, filePath: Path): Unit =
    saveDataset(dataset, Files.newOutputStream(filePath))

  def saveDataset(dataset: Attributes, outputStream: OutputStream): Unit = {
    var dos: DicomOutputStream = null
    try {
      dos = new DicomOutputStream(outputStream, defaultTransferSyntax)
      val metaInformation = dataset.createFileMetaInformation(defaultTransferSyntax)
      dos.writeDataset(metaInformation, dataset)
    } finally {
      SafeClose.close(dos)
    }
  }

  def loadDataset(path: Path, withPixelData: Boolean, useBulkDataURI: Boolean): Attributes =
    loadDataset(new BufferedInputStream(Files.newInputStream(path)), withPixelData, useBulkDataURI)

  def loadDataset(byteArray: Array[Byte], withPixelData: Boolean, useBulkDataURI: Boolean): Attributes =
    loadDataset(new BufferedInputStream(new ByteArrayInputStream(byteArray)), withPixelData, useBulkDataURI)

  def loadDataset(inputStream: InputStream, withPixelData: Boolean, useBulkDataURI: Boolean): Attributes = {
    var dis: DicomInputStream = null
    try {
      dis = new DicomInputStream(inputStream)

      val dataset =
        if (withPixelData) {
          if (useBulkDataURI)
            dis.setIncludeBulkData(IncludeBulkData.URI)
          else
            dis.setIncludeBulkData(IncludeBulkData.YES)
          dis.readDataset(-1, -1)
        } else {
          dis.setIncludeBulkData(IncludeBulkData.NO)
          dis.readDataset(-1, Tag.PixelData)
        }

      dataset
    } catch {
      case _: Exception => null
    } finally {
      SafeClose.close(dis)
    }
  }

  def loadJpegDataset(path: Path): Attributes =
    loadJpegDataset(new BufferedInputStream(Files.newInputStream(path)))

  def loadJpegDataset(inputStream: InputStream): Attributes = {
    var dis: DicomInputStream = null
    try {
      dis = new DicomInputStream(inputStream)
      dis.setIncludeBulkData(IncludeBulkData.URI)
      dis.setBulkDataDescriptor(BulkDataDescriptor.PIXELDATA)
      dis.readDataset(-1, -1)
    } catch {
      case _: Exception => null
    } finally {
      SafeClose.close(dis)
    }
  }
  def toByteArray(path: Path): Array[Byte] = toByteArray(loadDataset(path, withPixelData = true, useBulkDataURI = false))

  def toByteArray(dataset: Attributes): Array[Byte] = {
    val bos = new ByteArrayOutputStream
    saveDataset(dataset, bos)
    bos.close()
    bos.toByteArray
  }

  def datasetToPatient(dataset: Attributes): Patient =
    Patient(
      -1,
      PatientName(valueOrEmpty(dataset, DicomProperty.PatientName.dicomTag)),
      PatientID(valueOrEmpty(dataset, DicomProperty.PatientID.dicomTag)),
      PatientBirthDate(valueOrEmpty(dataset, DicomProperty.PatientBirthDate.dicomTag)),
      PatientSex(valueOrEmpty(dataset, DicomProperty.PatientSex.dicomTag)))

  def datasetToStudy(dataset: Attributes): Study =
    Study(
      -1,
      -1,
      StudyInstanceUID(valueOrEmpty(dataset, DicomProperty.StudyInstanceUID.dicomTag)),
      StudyDescription(valueOrEmpty(dataset, DicomProperty.StudyDescription.dicomTag)),
      StudyDate(valueOrEmpty(dataset, DicomProperty.StudyDate.dicomTag)),
      StudyID(valueOrEmpty(dataset, DicomProperty.StudyID.dicomTag)),
      AccessionNumber(valueOrEmpty(dataset, DicomProperty.AccessionNumber.dicomTag)),
      PatientAge(valueOrEmpty(dataset, DicomProperty.PatientAge.dicomTag)))

  def datasetToSeries(dataset: Attributes): Series =
    Series(
      -1,
      -1,
      SeriesInstanceUID(valueOrEmpty(dataset, DicomProperty.SeriesInstanceUID.dicomTag)),
      SeriesDescription(valueOrEmpty(dataset, DicomProperty.SeriesDescription.dicomTag)),
      SeriesDate(valueOrEmpty(dataset, DicomProperty.SeriesDate.dicomTag)),
      Modality(valueOrEmpty(dataset, DicomProperty.Modality.dicomTag)),
      ProtocolName(valueOrEmpty(dataset, DicomProperty.ProtocolName.dicomTag)),
      BodyPartExamined(valueOrEmpty(dataset, DicomProperty.BodyPartExamined.dicomTag)),
      Manufacturer(valueOrEmpty(dataset, DicomProperty.Manufacturer.dicomTag)),
      StationName(valueOrEmpty(dataset, DicomProperty.StationName.dicomTag)),
      FrameOfReferenceUID(valueOrEmpty(dataset, DicomProperty.FrameOfReferenceUID.dicomTag)))

  def datasetToImage(dataset: Attributes): Image =
    Image(
      -1,
      -1,
      SOPInstanceUID(valueOrEmpty(dataset, DicomProperty.SOPInstanceUID.dicomTag)),
      ImageType(readMultiple(dataset.getStrings(DicomProperty.ImageType.dicomTag))),
      InstanceNumber(valueOrEmpty(dataset, DicomProperty.InstanceNumber.dicomTag)))

  private def valueOrEmpty(dataset: Attributes, tag: Int) = Option(dataset.getString(tag)).getOrElse("")

  def readMultiple(values: Array[String]): String =
    if (values == null || values.length == 0)
      ""
    else
      values.tail.foldLeft(values.head)((result, part) => result + "/" + part)

  def checkSopClass(dataset: Attributes) =
    SopClasses.sopClasses
      .filter(sopClass => sopClass.included)
      .map(_.sopClassUID)
      .contains(dataset.getString(Tag.SOPClassUID))

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

  def readImageAttributes(dataset: Attributes): List[ImageAttribute] =
    readImageAttributes(dataset, 0, Nil, Nil)

  def readImageAttributes(dataset: Attributes, depth: Int, tagPath: List[Int], namePath: List[String]): List[ImageAttribute] = {
    val attributesBuffer = ListBuffer.empty[ImageAttribute]
    if (dataset != null) {
      dataset.accept(new Visitor() {
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

}
