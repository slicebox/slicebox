package se.nimsa.sbx.storage

import java.io.{BufferedInputStream, InputStream}
import java.nio.file.{Files, Path, Paths}
import javax.imageio.ImageIO

import org.dcm4che3.data.Attributes
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.{DicomData, DicomUtil, ImageAttribute}
import se.nimsa.sbx.storage.StorageProtocol.ImageInformation

import scala.util.control.NonFatal

/**
  * Service that stores DICOM files in local file system.
  *
  * @param path relative path to directory for DICOM files
  */
class FileStorage(val path: Path) extends StorageService {

  createStorageDirectoryIfNecessary()

  override def storeDicomData(dicomData: DicomData, image: Image): Boolean = {
    val storedPath = filePath(image)
    val overwrite = Files.exists(storedPath)
    try saveDicomData(dicomData, storedPath) catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException("Dicom data could not be stored", e)
    }
    overwrite
  }

  private def filePath(image: Image) =
    path.resolve(imageName(image))

  private def resolvePath(image: Image): Option[Path] =
    Option(filePath(image))
      .filter(p => Files.exists(p) && Files.isReadable(p))

  override def deleteFromStorage(image: Image): Unit =
    resolvePath(image) match {
      case Some(imagePath) =>
        Files.delete(imagePath)
      case None =>
    }

  override def readDicomData(image: Image, withPixelData: Boolean): Option[DicomData] =
    resolvePath(image).map { imagePath =>
      loadDicomData(imagePath, withPixelData)
    }

  override def readImageAttributes(image: Image): Option[List[ImageAttribute]] =
    resolvePath(image).map { imagePath =>
      DicomUtil.readImageAttributes(loadDicomData(imagePath, withPixelData = false).attributes)
    }

  def readImageInformation(image: Image): Option[ImageInformation] =
    resolvePath(image).map { imagePath =>
      super.readImageInformation(new BufferedInputStream(Files.newInputStream(imagePath)))
    }

  override def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Option[Array[Byte]] =
    resolvePath(image).map { imagePath =>
      val file = imagePath.toFile
      val iis = ImageIO.createImageInputStream(file)
      super.readPngImageData(iis, frameNumber, windowMin, windowMax, imageHeight)
    }

  override def imageAsInputStream(image: Image): Option[InputStream] =
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
