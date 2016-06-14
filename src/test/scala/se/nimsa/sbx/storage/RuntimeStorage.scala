package se.nimsa.sbx.storage

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.{DicomData, DicomUtil, ImageAttribute}
import se.nimsa.sbx.storage.StorageProtocol.ImageInformation

class RuntimeStorage extends StorageService {

  import scala.collection.mutable

  val storage = mutable.Map.empty[String, Array[Byte]]

  override def storeDicomData(dicomData: DicomData, image: Image): Boolean = {
    val overwrite = storage.contains(imageName(image))
    storage.put(imageName(image), toByteArray(dicomData))
    overwrite
  }

  override def deleteFromStorage(image: Image): Unit =
    storage.remove(imageName(image))

  override def readDicomData(image: Image, withPixelData: Boolean): Option[DicomData] =
    storage.get(imageName(image)).map(bytes => loadDicomData(bytes, withPixelData))

  override def readImageAttributes(image: Image): Option[List[ImageAttribute]] =
    storage.get(imageName(image)).map(bytes => DicomUtil.readImageAttributes(loadDicomData(bytes, withPixelData = false).attributes))

  override def readImageInformation(image: Image): Option[ImageInformation] =
    imageAsInputStream(image).map(is => super.readImageInformation(is))

  override def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Option[Array[Byte]] =
    imageAsInputStream(image).map(is => super.readPngImageData( ImageIO.createImageInputStream(is), frameNumber, windowMin, windowMax, imageHeight))

  override def imageAsInputStream(image: Image): Option[InputStream] =
    storage.get(imageName(image)).map(bytes => new ByteArrayInputStream(bytes))

  def clear() =
    storage.clear()
}
