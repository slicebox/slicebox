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

package se.nimsa.sbx.storage

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.LazyLogging
import org.dcm4che3.data.Tag
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam
import se.nimsa.dcm4che.streams.{DicomAttributesSink, DicomFlows, DicomPartFlow}
import se.nimsa.sbx.dicom.ImageAttribute
import se.nimsa.sbx.dicom.streams.DicomStreamOps
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.storage.StorageProtocol.ImageInformation

import scala.concurrent.{ExecutionContext, Future}


/**
  * Created by michaelkober on 2016-04-25.
  */
trait StorageService extends LazyLogging {

  def imageName(imageId: Long): String = imageId.toString

  def deleteByName(name: Seq[String]): Unit

  def deleteFromStorage(imageIds: Seq[Long]): Unit = deleteByName(imageIds.map(imageName))

  def move(sourceImageName: String, targetImageName: String): Unit

  def readImageAttributes(imageId: Long)(implicit materializer: Materializer, ec: ExecutionContext): Source[ImageAttribute, NotUsed] =
    DicomStreamOps.imageAttributesSource(fileSource(imageId))

  private val imageInformationTags = Seq(Tag.InstanceNumber, Tag.ImageIndex, Tag.NumberOfFrames, Tag.SmallestImagePixelValue, Tag.LargestImagePixelValue).sorted

  def readImageInformation(imageId: Long)(implicit materializer: Materializer, ec: ExecutionContext): Future[ImageInformation] =
    fileSource(imageId)
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

  def readPngImageData(imageId: Long, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int)(implicit materializer: Materializer, ec: ExecutionContext): Future[Array[Byte]] = Future {
    val source = fileSource(imageId)
    // dcm4che does not support viewing of deflated data, cf. Github issue #42
    // As a workaround, do streaming inflate and mapping of transfer syntax
    val inflatedSource = DicomStreamOps.inflatedSource(source)
    val is = inflatedSource.runWith(StreamConverters.asInputStream())
    val iis = ImageIO.createImageInputStream(is)

    val imageReader = ImageIO.getImageReadersByFormatName("DICOM").next
    imageReader.setInput(iis)
    val param = imageReader.getDefaultReadParam.asInstanceOf[DicomImageReadParam]
    if (windowMin < windowMax) {
      param.setWindowCenter((windowMax - windowMin) / 2)
      param.setWindowWidth(windowMax - windowMin)
    }

    try {
      val bi = try {
        val image = imageReader.read(frameNumber - 1, param)
        scaleImage(image, imageHeight)
      } catch {
        case e: NotFoundException => throw e
        case e: Exception => logger.error("Exception:", e)
          throw new IllegalArgumentException(e)
      }
      val baos = new ByteArrayOutputStream
      ImageIO.write(bi, "png", baos)
      baos.close()
      baos.toByteArray
    } finally {
      iis.close()
    }
  }

  private def scaleImage(image: BufferedImage, imageHeight: Int): BufferedImage = {
    val ratio = imageHeight / image.getHeight.asInstanceOf[Double]
    if (ratio != 0.0 && ratio != 1.0) {
      val imageWidth = (image.getWidth * ratio).asInstanceOf[Int]
      val resized = new BufferedImage(imageWidth, imageHeight, image.getType)
      val g = resized.createGraphics()
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      g.drawImage(image, 0, 0, imageWidth, imageHeight, 0, 0, image.getWidth, image.getHeight, null)
      g.dispose()
      resized
    } else {
      image
    }

  }

  /** Sink for dicom files. */
  def fileSink(name: String)(implicit executionContext: ExecutionContext): Sink[ByteString, Future[Done]]

  /**
    * Source for dicom files.
    *
    * @throws NotFoundException if data cannot be found for imageId
    **/
  def fileSource(imageId: Long): Source[ByteString, NotUsed] = fileSource(imageName(imageId))

  /**
    * Source for dicom files.
    *
    * @throws NotFoundException if data cannot be found for name
    **/
  def fileSource(name: String): Source[ByteString, NotUsed]

}

