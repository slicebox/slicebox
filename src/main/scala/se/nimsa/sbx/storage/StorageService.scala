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

package se.nimsa.sbx.storage

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, InputStream}
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

import com.amazonaws.util.IOUtils
import org.dcm4che3.data.{BulkData, Fragments, Tag}
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.{DicomData, ImageAttribute}
import se.nimsa.sbx.storage.StorageProtocol.ImageInformation

/**
  * Created by michaelkober on 2016-04-25.
  */
trait StorageService {

  val bufferSize = 524288

  def storeDicomData(dicomData: DicomData, image: Image): Boolean

  def imageName(image: Image) = image.id.toString

  def deleteFromStorage(images: Seq[Image]): Unit = images foreach (deleteFromStorage(_))

  def deleteFromStorage(image: Image): Unit

  def readDicomData(image: Image, withPixelData: Boolean): DicomData

  def readImageAttributes(image: Image): List[ImageAttribute]

  def readImageInformation(image: Image): ImageInformation

  def readImageInformation(inputStream: InputStream): ImageInformation = {
    val dicomData = loadDicomData(inputStream, withPixelData = false)
    val attributes = dicomData.attributes
    val instanceNumber = attributes.getInt(Tag.InstanceNumber, 1)
    val imageIndex = attributes.getInt(Tag.ImageIndex, 1)
    val frameIndex = if (instanceNumber > imageIndex) instanceNumber else imageIndex
    ImageInformation(
      attributes.getInt(Tag.NumberOfFrames, 1),
      frameIndex,
      attributes.getInt(Tag.SmallestImagePixelValue, 0),
      attributes.getInt(Tag.LargestImagePixelValue, 0))
  }

  def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Array[Byte]

  def readPngImageData(iis: ImageInputStream, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Array[Byte] = {
    try {
      val imageReader = ImageIO.getImageReadersByFormatName("DICOM").next
      imageReader.setInput(iis)
      val param = imageReader.getDefaultReadParam.asInstanceOf[DicomImageReadParam]
      if (windowMin < windowMax) {
        param.setWindowCenter((windowMax - windowMin) / 2)
        param.setWindowWidth(windowMax - windowMin)
      }
      val bi = try
        scaleImage(imageReader.read(frameNumber - 1, param), imageHeight)
      catch {
        case e: Exception => throw new IllegalArgumentException(e)
      }
      val baos = new ByteArrayOutputStream
      ImageIO.write(bi, "png", baos)
      baos.close()
      baos.toByteArray
    } finally {
      iis.close()
    }
  }

  def imageAsInputStream(image: Image): InputStream

  def imageAsByteArray(image: Image): Array[Byte] = {
    val is = imageAsInputStream(image)
    try {
      IOUtils.toByteArray(is)
    } finally {
      IOUtils.closeQuietly(is, null)
    }
  }

  def scaleImage(image: BufferedImage, imageHeight: Int): BufferedImage = {
    val ratio = imageHeight / image.getHeight.asInstanceOf[Double]
    if (ratio != 0.0 && ratio != 1.0) {
      val imageWidth = (image.getWidth * ratio).asInstanceOf[Int]
      val resized = new BufferedImage(imageWidth, imageHeight, image.getType)
      val g = resized.createGraphics()
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      g.drawImage(image, 0, 0, imageWidth, imageHeight, 0, 0, image.getWidth, image.getHeight, null)
      g.dispose()
      resized
    } else
      image
  }

}
