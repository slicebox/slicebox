package se.nimsa.sbx.storage

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

import org.dcm4che3.data.Attributes
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.{DicomUtil, ImageAttribute}
import se.nimsa.sbx.storage.StorageProtocol.ImageInformation

class RuntimeStorage extends StorageService {

  import scala.collection.mutable

  val storage = mutable.Map.empty[String, Array[Byte]]

  def storeDataset(dataset: Attributes, image: Image): Boolean = {
    val overwrite = storage.contains(imageName(image))
    storage.put(imageName(image), toByteArray(dataset))
    overwrite
  }

  def storeEncapsulated(image: Image, dcmTempPath: Path): Unit = {
    storage.put(imageName(image), Files.readAllBytes(dcmTempPath))
    Files.delete(dcmTempPath)
  }

  def deleteFromStorage(image: Image): Unit =
    storage.remove(imageName(image))

  def readDataset(image: Image, withPixelData: Boolean, useBulkDataURI: Boolean): Option[Attributes] =
    storage.get(imageName(image)).map(bytes => loadDataset(bytes, withPixelData, useBulkDataURI))

  def readImageAttributes(image: Image): Option[List[ImageAttribute]] =
    storage.get(imageName(image)).map(bytes => DicomUtil.readImageAttributes(loadDataset(bytes, withPixelData = false, useBulkDataURI = false)))

  def readImageInformation(image: Image): Option[ImageInformation] =
    imageAsInputStream(image).map(is => super.readImageInformation(is))

  def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Option[Array[Byte]] =
    imageAsInputStream(image).map(is => super.readPngImageData( ImageIO.createImageInputStream(is), frameNumber, windowMin, windowMax, imageHeight))

  def readSecondaryCaptureJpeg(image: Image, imageHeight: Int): Option[Array[Byte]] =
    imageAsInputStream(image).map(is => super.readSecondaryCaptureJpeg(is, imageHeight))

  def imageAsInputStream(image: Image): Option[InputStream] =
    storage.get(imageName(image)).map(bytes => new ByteArrayInputStream(bytes))

  def clear() =
    storage.clear()
}
