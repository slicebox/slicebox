package se.nimsa.sbx.storage

import java.io.{ByteArrayInputStream, InputStream}
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

  override def readDicomData(image: Image, withPixelData: Boolean): DicomData =
    loadDicomData(storage(imageName(image)), withPixelData)

  override def readImageAttributes(image: Image): List[ImageAttribute] =
    DicomUtil.readImageAttributes(loadDicomData(storage(imageName(image)), withPixelData = false).attributes)

  override def readImageInformation(image: Image): ImageInformation =
    super.readImageInformation(imageAsInputStream(image))

  override def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Array[Byte] =
    super.readPngImageData(ImageIO.createImageInputStream(imageAsInputStream(image)), frameNumber, windowMin, windowMax, imageHeight)

  override def imageAsInputStream(image: Image): InputStream =
    new ByteArrayInputStream(storage(imageName(image)))

  def clear() =
    storage.clear()
}
