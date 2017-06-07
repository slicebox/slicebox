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
import java.io.{ByteArrayOutputStream, InputStream}
import javax.imageio.ImageIO

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.amazonaws.util.IOUtils
import org.dcm4che3.data.Tag
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam
import se.nimsa.dcm4che.streams.{DicomAttributesSink, DicomFlows, DicomPartFlow}
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.streams.DicomStreams
import se.nimsa.sbx.dicom.{DicomData, ImageAttribute}
import se.nimsa.sbx.storage.StorageProtocol.ImageInformation

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by michaelkober on 2016-04-25.
  */
trait StorageService {

  val bufferSize = 524288

  def storeDicomData(dicomData: DicomData, image: Image): Boolean

  def imageName(image: Image) = image.id.toString

  def deleteFromStorage(name: String): Unit

  def deleteFromStorage(images: Seq[Image]): Unit = images foreach deleteFromStorage

  def deleteFromStorage(image: Image): Unit

  def move(sourceImageName: String, targetImageName: String): Unit

  def readDicomData(image: Image, withPixelData: Boolean): DicomData

  def readImageAttributes(image: Image)(implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer): Source[ImageAttribute, NotUsed] =
    DicomStreams.imageAttributesSource(fileSource(image))

  private val imageInformationTags = Seq(Tag.InstanceNumber, Tag.ImageIndex, Tag.NumberOfFrames, Tag.SmallestImagePixelValue, Tag.LargestImagePixelValue).sorted

  def readImageInformation(image: Image)(implicit ec: ExecutionContext, actorSystem: ActorSystem, mat: Materializer): Future[ImageInformation] = {
    fileSource(image)
      .via(new DicomPartFlow(stopTag = Some(imageInformationTags.last + 1)))
      .via(DicomFlows.whitelistFilter(imageInformationTags.contains _))
      .via(DicomFlows.attributeFlow)
      .runWith(DicomAttributesSink.attributesSink)
      .map {
        case (_, maybeAttributes) =>
          maybeAttributes.map { attributes =>
            val instanceNumber = attributes.getInt(Tag.InstanceNumber, 1)
            val imageIndex = attributes.getInt(Tag.ImageIndex, 1)
            val frameIndex = if (instanceNumber > imageIndex) instanceNumber else imageIndex
            ImageInformation(
              attributes.getInt(Tag.NumberOfFrames, 1),
              frameIndex,
              attributes.getInt(Tag.SmallestImagePixelValue, 0),
              attributes.getInt(Tag.LargestImagePixelValue, 0))
          }.getOrElse(ImageInformation(0, 0, 0, 0))
      }
  }

  def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int)
                      (implicit system: ActorSystem, materializer: Materializer): Array[Byte]

  def readPngImageData(source: Source[ByteString, _], frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int)
                      (implicit system: ActorSystem, materializer: Materializer): Array[Byte] = {
    // dcm4che does not support viewing of deflated data, cf. Github issue #42
    // As a workaround, do streaming inflate and mapping of transfer syntax
    val inflatedSource = DicomStreams.inflatedSource(source)
    val is = inflatedSource.runWith(StreamConverters.asInputStream())
    val iis = ImageIO.createImageInputStream(is)
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

  /** Sink for dicom files. */
  def fileSink(name: String)(implicit actorSystem: ActorSystem, mat: Materializer, ec: ExecutionContext): Sink[ByteString, Future[Done]]

  /** Source for dicom files. */
  def fileSource(image: Image)(implicit actorSystem: ActorSystem, mat: Materializer): Source[ByteString, NotUsed]

}

