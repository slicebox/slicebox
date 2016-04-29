package se.nimsa.sbx.storage

import java.io.{InputStream, BufferedInputStream}
import java.nio.file.{Path, Paths, Files}
import javax.imageio.ImageIO

import org.dcm4che3.data.Attributes
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.{DicomUtil, ImageAttribute}
import se.nimsa.sbx.storage.StorageProtocol.ImageInformation

import scala.util.control.NonFatal

/**
  * Service that stores DICOM files in local file system.
  *
  * @param path relative path to directory for DICOM files
  */
class FileStorage(val path: Path) extends StorageService {

  createStorageDirectoryIfNecessary()

  def storeDataset(dataset: Attributes, image: Image): Boolean = {
    val storedPath = filePath(image)
    val overwrite = Files.exists(storedPath)
    try saveDataset(dataset, storedPath) catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException("Dataset file could not be stored", e)
    }
    overwrite
  }

  def filePath(image: Image) =
    path.resolve(imageName(image))

  def storeEncapsulated(image: Image, dcmTempPath: Path): Unit =
    Files.move(dcmTempPath, filePath(image))

  def resolvePath(image: Image): Option[Path] =
    Option(filePath(image))
      .filter(p => Files.exists(p) && Files.isReadable(p))

  def deleteFromStorage(image: Image): Unit =
    resolvePath(image) match {
      case Some(imagePath) =>
        Files.delete(imagePath)
        //log.info(s"Deleted dataset with image id ${image.id}")
      case None =>
        //log.warning(s"No DICOM file found for image with id ${image.id} when deleting dataset")
    }

  def readDataset(image: Image, withPixelData: Boolean, useBulkDataURI: Boolean): Option[Attributes] =
    resolvePath(image).map { imagePath =>
      loadDataset(imagePath, withPixelData, useBulkDataURI)
    }

  def readImageAttributes(image: Image): Option[List[ImageAttribute]] =
    resolvePath(image).map { imagePath =>
      DicomUtil.readImageAttributes(loadDataset(imagePath, withPixelData = false, useBulkDataURI = false))
    }

  def readImageInformation(image: Image): Option[ImageInformation] =
    resolvePath(image).map { imagePath =>
      super.readImageInformation(new BufferedInputStream(Files.newInputStream(imagePath)))
    }

  def readImageFrame(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Option[Array[Byte]] =
    resolvePath(image).map { imagePath =>
      val file = imagePath.toFile
      val iis = ImageIO.createImageInputStream(file)
      super.readImageFrame(iis, frameNumber, windowMin, windowMax, imageHeight)
    }

  def readSecondaryCaptureJpeg(image: Image, imageHeight: Int): Option[Array[Byte]] =
    resolvePath(image).map { imagePath =>
      super.readSecondaryCaptureJpeg(new BufferedInputStream(Files.newInputStream(imagePath)), imageHeight)
    }

  def imageAsInputStream(image: Image): Option[InputStream] =
    resolvePath(image).map { imagePath =>
      new BufferedInputStream(Files.newInputStream(imagePath))
    }

  private def createStorageDirectoryIfNecessary(): Unit = {
    if (!Files.exists(path))
      try {
        Files.createDirectories(path)
      } catch {
        case e: Exception => throw new RuntimeException("Dicom-files directory could not be created: " + e.getMessage)
      }
    if (!Files.isDirectory(path))
      throw new IllegalArgumentException("Dicom-files directory is not a directory.")
  }
}
