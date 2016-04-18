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

package se.nimsa.sbx.storage

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.file.{Files, NoSuchFileException, Path}
import java.util.zip.{ZipEntry, ZipOutputStream}
import javax.imageio.ImageIO

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import org.dcm4che3.data.{Attributes, BulkData, Fragments, Tag}
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam
import se.nimsa.sbx.app.GeneralProtocol.Source
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.{DicomUtil, ImageAttribute, Jpg2Dcm}
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.storage.StorageProtocol._

import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

class StorageServiceActor(storage: Path) extends Actor {

  val bufferSize = 524288

  val log = Logging(context.system, this)

  log.info("Storage service started")

  def receive = LoggingReceive {

    case CheckDataset(dataset) =>
      sender ! checkDataset(dataset)

    case AddDataset(dataset, image) =>
      if (checkDataset(dataset)) {
        val overwrite = storeDataset(dataset, image)
        if (overwrite)
          log.info(s"Updated existing file with image id ${image.id}")
        else
          log.info(s"Stored file with image id ${image.id}")
        val datasetAdded = DatasetAdded(image, overwrite)
        context.system.eventStream.publish(datasetAdded)
        sender ! datasetAdded
      }

    case AddJpeg(jpegBytes, patient, study, source) =>
      val dcmTempPath = Files.createTempFile("slicebox-sc-", "")
      val attributes = Jpg2Dcm(jpegBytes, patient, study, dcmTempPath.toFile)
      val series = datasetToSeries(attributes)
      val image = datasetToImage(attributes)
      storeEncapsulated(patient, study, series, image, source, dcmTempPath)

      log.info(s"Stored encapsulated JPEG with image id ${image.id}")
      context.system.eventStream.publish(DatasetAdded(image, overwrite = false))

      sender ! image

    case DeleteDataset(image) =>
      val datasetDeleted = DatasetDeleted(image)
      try {
        deleteFromStorage(image)
        context.system.eventStream.publish(datasetDeleted)
      } catch {
        case e: NoSuchFileException => log.info(s"DICOM file for image with id ${image.id} could not be found, no need to delete.")
      }
      sender ! datasetDeleted

    case CreateTempZipFile(imagesAndSeries) =>
      val zipFilePath = createTempZipFile(imagesAndSeries)
      sender ! FileName(zipFilePath.getFileName.toString)

    case DeleteTempZipFile(path) =>
      Files.deleteIfExists(path)

    case msg: ImageRequest =>
      msg match {

        case GetImagePath(image) =>
          sender ! resolvePath(image).map(ImagePath(_))

        case GetDataset(image, withPixelData) =>
          sender ! readDataset(image, withPixelData)

        case GetImageAttributes(image) =>
          sender ! readImageAttributes(image)

        case GetImageInformation(image) =>
          sender ! readImageInformation(image)

        case GetImageFrame(image, frameNumber, windowMin, windowMax, imageHeight) =>
          val imageBytes: Option[Array[Byte]] =
            try
              readImageFrame(image, frameNumber, windowMin, windowMax, imageHeight)
            catch {
              case NonFatal(e) =>
                readSecondaryCaptureJpeg(image, imageHeight)
            }
          sender ! imageBytes

      }

  }

  def checkDataset(dataset: Attributes): Boolean = {
    if (dataset == null)
      throw new IllegalArgumentException("Invalid dataset")
    else if (!DicomUtil.checkSopClass(dataset))
      throw new IllegalArgumentException(s"Unsupported SOP Class UID ${dataset.getString(Tag.SOPClassUID)}")
    true
  }

  def storeDataset(dataset: Attributes, image: Image): Boolean = {
    val storedPath = filePath(image)
    val overwrite = Files.exists(storedPath)
    try saveDataset(dataset, storedPath) catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException("Dataset file could not be stored", e)
    }
    overwrite
  }

  def storeEncapsulated(dbPatient: Patient, dbStudy: Study,
                        series: Series, image: Image,
                        source: Source, dcmTempPath: Path): Image = {
    val storedPath = filePath(image)
    Files.move(dcmTempPath, storedPath)
    image
  }

  def filePath(image: Image) = storage.resolve(fileName(image))

  def fileName(image: Image) = image.id.toString

  def resolvePath(image: Image): Option[Path] =
    Some(filePath(image))
      .filter(Files.exists(_))
      .filter(Files.isReadable)

  def deleteFromStorage(images: Seq[Image]): Unit = images foreach (deleteFromStorage(_))

  def deleteFromStorage(image: Image): Unit =
    resolvePath(image) match {
      case Some(imagePath) =>
        Files.delete(imagePath)
        log.info(s"Deleted dataset with image id ${image.id}")
      case None =>
        log.warning(s"No DICOM file found for image with id ${image.id} when deleting dataset")
    }

  def readDataset(image: Image, withPixelData: Boolean): Option[Attributes] =
    resolvePath(image).map { imagePath =>
      loadDataset(imagePath, withPixelData)
    }

  def readImageAttributes(image: Image): Option[List[ImageAttribute]] =
    resolvePath(image).map { imagePath =>
      DicomUtil.readImageAttributes(loadDataset(imagePath, withPixelData = false))
    }

  def readImageInformation(image: Image): Option[ImageInformation] =
    resolvePath(image).map { imagePath =>
      val dataset = loadDataset(imagePath, withPixelData = false)
      val instanceNumber = dataset.getInt(Tag.InstanceNumber, 1)
      val imageIndex = dataset.getInt(Tag.ImageIndex, 1)
      val frameIndex = if (instanceNumber > imageIndex) instanceNumber else imageIndex
      ImageInformation(
        dataset.getInt(Tag.NumberOfFrames, 1),
        frameIndex,
        dataset.getInt(Tag.SmallestImagePixelValue, 0),
        dataset.getInt(Tag.LargestImagePixelValue, 0))
    }

  def readImageFrame(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Option[Array[Byte]] =
    resolvePath(image).map { imagePath =>
      val file = imagePath.toFile
      val iis = ImageIO.createImageInputStream(file)
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

  def readSecondaryCaptureJpeg(image: Image, imageHeight: Int) =
    resolvePath(image).map { imagePath =>
      val ds = loadJpegDataset(imagePath)
      val pd = ds.getValue(Tag.PixelData)
      if (pd != null && pd.isInstanceOf[Fragments]) {
        val fragments = pd.asInstanceOf[Fragments]
        if (fragments.size == 2) {
          fragments.get(1) match {
            case bd: BulkData =>
              val bytes = bd.toBytes(null, bd.bigEndian)
              val bi = scaleImage(ImageIO.read(new ByteArrayInputStream(bytes)), imageHeight)
              val baos = new ByteArrayOutputStream
              ImageIO.write(bi, "png", baos)
              baos.close()
              baos.toByteArray
            case _ =>
              throw new IllegalArgumentException("JPEG bytes not an instance of BulkData")
          }
        } else throw new IllegalArgumentException(s"JPEG fragements are expected to contain 2 entries, contained ${
          fragments.size
        }")
      } else throw new IllegalArgumentException("JPEG bytes not contained in Fragments")
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

  def createTempZipFile(imagesAndSeries: Seq[(Image, FlatSeries)]): Path = {

    def addToZipFile(path: Path, image: Image, flatSeries: FlatSeries, zos: ZipOutputStream): Unit = {

      def sanitize(string: String) = string.replace('/', '-').replace('\\', '-')

      val is = Files.newInputStream(path)
      val patientFolder = sanitize(s"${
        flatSeries.patient.id
      }_${
        flatSeries.patient.patientName.value
      }-${
        flatSeries.patient.patientID.value
      }")
      val studyFolder = sanitize(s"${
        flatSeries.study.id
      }_${
        flatSeries.study.studyDate.value
      }")
      val seriesFolder = sanitize(s"${
        flatSeries.series.id
      }_${
        flatSeries.series.seriesDate.value
      }_${
        flatSeries.series.modality.value
      }")
      val imageName = s"${
        image.id
      }.dcm"
      val entryName = s"$patientFolder/$studyFolder/$seriesFolder/$imageName"
      val zipEntry = new ZipEntry(entryName)
      zos.putNextEntry(zipEntry)

      val bytes = new Array[Byte](bufferSize)
      var bytesLeft = true
      while (bytesLeft) {
        val length = is.read(bytes)
        bytesLeft = length > 0
        if (bytesLeft) zos.write(bytes, 0, length)
      }

      zos.closeEntry()
      is.close()
    }

    def scheduleDeleteTempFile(tempFile: Path) =
      context.system.scheduler.scheduleOnce(12.hours, self, tempFile)

    val tempFile = Files.createTempFile("slicebox-export-", ".zip")
    val fos = Files.newOutputStream(tempFile)
    val zos = new ZipOutputStream(fos)

    // Add the files to the zip archive strictly sequentially (no concurrency) to
    // reduce load on meta data service and to keep memory consumption bounded
    imagesAndSeries.foreach {
      case (image, flatSeries) =>
        resolvePath(image).foreach { path =>
          addToZipFile(path, image, flatSeries, zos)
        }
    }

    zos.close()
    fos.close()

    scheduleDeleteTempFile(tempFile)

    tempFile
  }

}

object StorageServiceActor {
  def props(storage: Path): Props = Props(new StorageServiceActor(storage))
}
