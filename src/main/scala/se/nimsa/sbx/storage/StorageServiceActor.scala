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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.BulkData
import org.dcm4che3.data.Fragments
import org.dcm4che3.data.Tag
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam

import akka.actor.Actor
import akka.actor.Props
import akka.dispatch.ExecutionContexts
import akka.event.Logging
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import javax.imageio.ImageIO
import se.nimsa.sbx.app.GeneralProtocol.Source
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.ImageAttribute
import se.nimsa.sbx.dicom.Jpg2Dcm
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.util.FutureUtil
import se.nimsa.sbx.util.SbxExtensions._

class StorageServiceActor(storage: Path, implicit val timeout: Timeout) extends Actor {

  val bufferSize = 524288

  import context.system
  val log = Logging(context.system, this)

  implicit val ec = ExecutionContexts.fromExecutor(Executors.newWorkStealingPool())

  val metaDataService = context.actorSelection("../MetaDataService")

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[DatasetReceived])
    context.system.eventStream.subscribe(context.self, classOf[FileReceived])
  }

  log.info("Storage service started")

  def receive = LoggingReceive {

    case FileReceived(path, source) =>
      val dataset = loadDataset(path, true)
      if (dataset != null)
        if (checkSopClass(dataset))
          addMetadata(dataset, source).map { image =>
            val overwrite = storeDataset(dataset, image, source)
            if (overwrite)
              log.info(s"Updated existing file with image id ${image.id}")
            else
              log.info(s"Stored file with image id ${image.id}")
            context.system.eventStream.publish(DatasetAdded(image, source, overwrite))
          }
        else
          SbxLog.info("Storage", s"Received dataset with unsupported SOP Class UID ${dataset.getString(Tag.SOPClassUID)}, skipping")
      else
        log.debug("Storage", s"Dataset could not be read, skipping")

    case DatasetReceived(dataset, source) =>
      if (dataset != null)
        if (checkSopClass(dataset))
          addMetadata(dataset, source).map { image =>
            val overwrite = storeDataset(dataset, image, source)
            if (overwrite)
              log.info(s"Updated existing file with image id ${image.id}")
            else
              log.info(s"Stored file with image id ${image.id}")
            context.system.eventStream.publish(DatasetAdded(image, source, overwrite))
          }
        else
          SbxLog.info("Storage", s"Received dataset with unsupported SOP Class UID ${dataset.getString(Tag.SOPClassUID)}, skipping")
      else
        log.debug("Storage", s"Dataset could not be read, skipping")

    case AddDataset(dataset, source) =>
      val datasetAdded: Future[DatasetAdded] =
        if (dataset == null)
          Future.failed(new IllegalArgumentException("Invalid dataset"))
        else if (!checkSopClass(dataset))
          Future.failed(new IllegalArgumentException(s"Unsupported SOP Class UID ${dataset.getString(Tag.SOPClassUID)}"))
        else
          addMetadata(dataset, source).map { image =>
            val overwrite = storeDataset(dataset, image, source)
            if (overwrite)
              log.info(s"Updated existing file with image id ${image.id}")
            else
              log.info(s"Stored file with image id ${image.id}")
            val datasetAdded = DatasetAdded(image, source, overwrite)
            context.system.eventStream.publish(datasetAdded)
            datasetAdded
          }
      datasetAdded.pipeTo(sender)

    case AddJpeg(jpegBytes, studyId, source) =>
      val image: Future[Option[Image]] =
        studyById(studyId).flatMap { optionalStudy =>
          optionalStudy.map { study =>
            patientById(study.patientId).map { optionalPatient =>
              optionalPatient.map { patient =>
                val dcmTempPath = Files.createTempFile("slicebox-sc-", "")
                val attributes = Jpg2Dcm(jpegBytes, patient, study, dcmTempPath.toFile)
                val series = datasetToSeries(attributes)
                val image = datasetToImage(attributes)
                storeEncapsulated(patient, study, series, image, source, dcmTempPath)
              }
            }
          }.unwrap
        }.unwrap

      image.foreach(_.foreach { image =>
        log.info(s"Stored encapsulated JPEG with image id ${image.id}")
        context.system.eventStream.publish(DatasetAdded(image, source, false))
      })

      image.pipeTo(sender)

    case DeleteDataset(imageId) =>
      val datasetDeleted: Future[DatasetDeleted] =
        imageById(imageId).flatMap { optionalImage =>
          optionalImage.map { image =>
            try deleteFromStorage(image.id) catch {
              case e: NoSuchFileException => log.info(s"DICOM file for image with id $imageId could not be found, no need to delete.")
            }
            deleteMetaData(image)
          }
            .getOrElse(Future.successful {})
            .map(i => DatasetDeleted(imageId))
        }
      datasetDeleted.foreach {
        log.info(s"Deleted dataset with image id $imageId")
        context.system.eventStream.publish(_)
      }
      datasetDeleted.pipeTo(sender)

    case CreateTempZipFile(imageIds) =>
      createTempZipFile(imageIds)
        .map(path => FileName(path.getFileName.toString))
        .pipeTo(sender)

    case DeleteTempZipFile(path) =>
      Files.deleteIfExists(path)

    case msg: ImageRequest =>
      msg match {

        case GetImagePath(imageId) =>
          imagePathForId(imageId)
            .map(_.map(ImagePath(_)))
            .pipeTo(sender)

        case GetDataset(imageId, withPixelData) =>
          readDataset(imageId, withPixelData)
            .pipeTo(sender)

        case GetImageAttributes(imageId) =>
          readImageAttributes(imageId)
            .pipeTo(sender)

        case GetImageInformation(imageId) =>
          readImageInformation(imageId)
            .pipeTo(sender)

        case GetImageFrame(imageId, frameNumber, windowMin, windowMax, imageHeight) =>
          val imageBytes: Future[Option[Array[Byte]]] =
            readImageFrame(imageId, frameNumber, windowMin, windowMax, imageHeight)
              .recoverWith {
                case NonFatal(e) =>
                  readSecondaryCaptureJpeg(imageId, imageHeight)
              }
          imageBytes.pipeTo(sender)

      }

  }

  def storeDataset(dataset: Attributes, image: Image, source: Source): Boolean = {
    val storedPath = filePath(image)
    val overwrite = Files.exists(storedPath)
    try saveDataset(dataset, storedPath) catch {
      case NonFatal(e) =>
        deleteMetaData(image) // try to clean up, not interested in whether it worked or not        
        throw new IllegalArgumentException("Dataset file could not be stored", e)
    }
    overwrite
  }

  def storeEncapsulated(
    dbPatient: Patient, dbStudy: Study,
    series: Series, image: Image,
    source: Source, dcmTempPath: Path): Future[Image] =
    addMetadata(createDataset(dbPatient, dbStudy, series, image), source).map { dbImage =>
      val storedPath = filePath(dbImage)
      Files.move(dcmTempPath, storedPath)
      dbImage
    }

  def filePath(image: Image) = storage.resolve(fileName(image))
  def fileName(image: Image) = image.id.toString

  def imagePathForId(imageId: Long): Future[Option[Path]] =
    imageById(imageId).map {
      _.map(filePath(_))
        .filter(Files.exists(_))
        .filter(Files.isReadable(_))
    }

  def deleteFromStorage(imageIds: Seq[Long]): Future[Seq[Unit]] =
    Future.sequence {
      imageIds map (deleteFromStorage(_))
    }

  def deleteFromStorage(imageId: Long): Future[Unit] =
    imagePathForId(imageId).map { optionalImagePath =>
      optionalImagePath.map { imagePath =>
        Files.delete(imagePath)
        log.debug("Deleted file " + imagePath)
      }
    }

  def readDataset(imageId: Long, withPixelData: Boolean): Future[Option[Attributes]] =
    imagePathForId(imageId).map { optionalImagePath =>
      optionalImagePath.map { imagePath =>
        loadDataset(imagePath, withPixelData)
      }
    }

  def readImageAttributes(imageId: Long): Future[Option[List[ImageAttribute]]] =
    imagePathForId(imageId).map { optionalImagePath =>
      optionalImagePath.map { imagePath =>
        DicomUtil.readImageAttributes(loadDataset(imagePath, false))
      }
    }

  def readImageInformation(imageId: Long): Future[Option[ImageInformation]] =
    imagePathForId(imageId).map { optionalImagePath =>
      optionalImagePath.map { imagePath =>
        val dataset = loadDataset(imagePath, false)
        val instanceNumber = dataset.getInt(Tag.InstanceNumber, 1)
        val imageIndex = dataset.getInt(Tag.ImageIndex, 1)
        val frameIndex = if (instanceNumber > imageIndex) instanceNumber else imageIndex
        ImageInformation(
          dataset.getInt(Tag.NumberOfFrames, 1),
          frameIndex,
          dataset.getInt(Tag.SmallestImagePixelValue, 0),
          dataset.getInt(Tag.LargestImagePixelValue, 0))
      }
    }

  def readImageFrame(imageId: Long, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Future[Option[Array[Byte]]] =
    imagePathForId(imageId).map { optionalImagePath =>
      optionalImagePath.map { imagePath =>
        val file = imagePath.toFile
        val iis = ImageIO.createImageInputStream(file)
        try {
          val imageReader = ImageIO.getImageReadersByFormatName("DICOM").next
          imageReader.setInput(iis)
          val param = imageReader.getDefaultReadParam().asInstanceOf[DicomImageReadParam]
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
    }

  def readSecondaryCaptureJpeg(imageId: Long, imageHeight: Int) =
    imagePathForId(imageId).map { optionalImagePath =>
      optionalImagePath.map { imagePath =>
        val ds = loadJpegDataset(imagePath)
        val pd = ds.getValue(Tag.PixelData)
        if (pd != null && pd.isInstanceOf[Fragments]) {
          val fragments = pd.asInstanceOf[Fragments]
          if (fragments.size == 2) {
            val f1 = fragments.get(1)
            if (f1.isInstanceOf[BulkData]) {
              val bd = f1.asInstanceOf[BulkData]
              val bytes = bd.toBytes(null, bd.bigEndian)
              val bi = scaleImage(ImageIO.read(new ByteArrayInputStream(bytes)), imageHeight)
              val baos = new ByteArrayOutputStream
              ImageIO.write(bi, "png", baos)
              baos.close()
              baos.toByteArray
            } else throw new IllegalArgumentException("JPEG bytes not an instance of BulkData")
          } else throw new IllegalArgumentException(s"JPEG fragements are expected to contain 2 entries, contained ${fragments.size}")
        } else throw new IllegalArgumentException("JPEG bytes not contained in Fragments")
      }
    }

  def scaleImage(image: BufferedImage, imageHeight: Int): BufferedImage = {
    val ratio = imageHeight / image.getHeight.asInstanceOf[Double]
    if (ratio != 0.0 && ratio != 1.0) {
      val imageWidth = (image.getWidth * ratio).asInstanceOf[Int]
      val resized = new BufferedImage(imageWidth, imageHeight, image.getType)
      val g = resized.createGraphics()
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.drawImage(image, 0, 0, imageWidth, imageHeight, 0, 0, image.getWidth, image.getHeight, null);
      g.dispose()
      resized
    } else
      image
  }

  def createTempZipFile(imageIds: Seq[Long]): Future[Path] = {

    val tempFile = Files.createTempFile("slicebox-export-", ".zip")
    val fos = Files.newOutputStream(tempFile)
    val zos = new ZipOutputStream(fos);

    // Add the files to the zip archive strictly sequentially (no concurrency) to 
    // reduce load on meta data service and to keep memory consumption bounded
    val futureAddedFiles = FutureUtil.traverseSequentially(imageIds) { imageId =>
      val pathImageAndFlatSeries =
        imagePathForId(imageId).flatMap(_.map { path =>
          imageById(imageId).flatMap(_.map { image =>
            flatSeriesById(image.seriesId).map(_.map { flatSeries =>
              (path, image, flatSeries)
            })
          }.unwrap)
        }.unwrap)
      pathImageAndFlatSeries.map(_.map {
        case (path, image, flatSeries) =>
          addToZipFile(path, image, flatSeries, zos)
      })
    }

    def cleanup = {
      zos.close
      fos.close
      scheduleDeleteTempFile(tempFile)
    }

    futureAddedFiles.onFailure { case _ => cleanup }

    futureAddedFiles.map { u => cleanup; tempFile }
  }

  def addToZipFile(path: Path, image: Image, flatSeries: FlatSeries, zos: ZipOutputStream): Unit = {

    def sanitize(string: String) = string.replace('/', '-').replace('\\', '-')

    val is = Files.newInputStream(path)
    val patientFolder = sanitize(s"${flatSeries.patient.id}_${flatSeries.patient.patientName.value}-${flatSeries.patient.patientID.value}")
    val studyFolder = sanitize(s"${flatSeries.study.id}_${flatSeries.study.studyDate.value}")
    val seriesFolder = sanitize(s"${flatSeries.series.id}_${flatSeries.series.seriesDate.value}_${flatSeries.series.modality.value}")
    val imageName = s"${image.id}.dcm"
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

    zos.closeEntry
    is.close
  }

  def scheduleDeleteTempFile(tempFile: Path) =
    context.system.scheduler.scheduleOnce(12.hours, self, tempFile)

  def patientById(patientId: Long): Future[Option[Patient]] =
    metaDataService.ask(GetPatient(patientId)).mapTo[Option[Patient]]

  def studyById(studyId: Long): Future[Option[Study]] =
    metaDataService.ask(GetStudy(studyId)).mapTo[Option[Study]]

  def imageById(imageId: Long): Future[Option[Image]] =
    metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]

  def flatSeriesById(seriesId: Long): Future[Option[FlatSeries]] =
    metaDataService.ask(GetSingleFlatSeries(seriesId)).mapTo[Option[FlatSeries]]

  def addMetadata(dataset: Attributes, source: Source): Future[Image] =
    metaDataService.ask(
      AddMetaData(
        datasetToPatient(dataset),
        datasetToStudy(dataset),
        datasetToSeries(dataset),
        datasetToImage(dataset), source))
      .mapTo[MetaDataAdded]
      .map(_.image)

  def deleteMetaData(image: Image): Future[Unit] =
    metaDataService.ask(DeleteMetaData(image.id)).map(any => {})

}

object StorageServiceActor {
  def props(storage: Path, timeout: Timeout): Props = Props(new StorageServiceActor(storage, timeout))
}
