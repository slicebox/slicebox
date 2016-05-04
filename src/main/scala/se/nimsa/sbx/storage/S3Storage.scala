package se.nimsa.sbx.storage

import java.io.{InputStream, ByteArrayOutputStream}
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

import org.dcm4che3.data.Attributes
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.{DicomUtil, ImageAttribute}
import se.nimsa.sbx.storage.StorageProtocol.ImageInformation

import scala.util.control.NonFatal

/**
  * Service that stores DICOM files on AWS S3.
  * @param s3Prefix prefix for keys
  * @param bucket S3 bucket
  */
class S3Storage(val bucket: String, val s3Prefix: String) extends StorageService {

  val s3Client = new S3Facade(bucket)

  def s3Id(image: Image) =
    s3Prefix + "/" + imageName(image)


  def storeDataset(dataset: Attributes, image: Image): Boolean = {
    val storedId = s3Id(image)
    val overwrite = s3Client.exists(storedId)
    try saveDatasetToS3(dataset, storedId) catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException("Dataset file could not be stored", e)
    }
    overwrite
  }

  def saveDatasetToS3(dataset: Attributes, s3Key: String): Unit = {
    val os = new ByteArrayOutputStream()
    try saveDataset(dataset, os) catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException("Dataset file could not be stored", e)
    }
    val buffer = os.toByteArray
    s3Client.upload(s3Key, buffer)
  }

  def storeEncapsulated(image: Image, dcmTempPath: Path): Unit = {
    s3Client.upload(s3Id(image),Files.readAllBytes(dcmTempPath))
    Files.delete(dcmTempPath)
  }

  def deleteFromStorage(image: Image): Unit = s3Client.delete(s3Id(image))

  def readDataset(image: Image, withPixelData: Boolean, useBulkDataURI: Boolean): Option[Attributes] = {
    val s3InputStream = s3Client.get(s3Id(image))
    Some(loadDataset(s3InputStream, withPixelData, useBulkDataURI))
  }

  def readImageAttributes(image: Image): Option[List[ImageAttribute]] = {
    val s3InputStream = s3Client.get(s3Id(image))
    Some(DicomUtil.readImageAttributes(loadDataset(s3InputStream, withPixelData = false, useBulkDataURI = false)))
  }

  def readImageInformation(image: Image): Option[ImageInformation] = {
    val s3InputStream = s3Client.get(s3Id(image))
    Some(super.readImageInformation(s3InputStream))
  }

  def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Option[Array[Byte]] = {
    val s3InputStream = s3Client.get(s3Id(image))
    val iis = ImageIO.createImageInputStream(s3InputStream)
    Some(super.readPngImageData(iis, frameNumber, windowMin, windowMax, imageHeight))
  }

  def readSecondaryCaptureJpeg(image: Image, imageHeight: Int): Option[Array[Byte]] = {
    val s3InputStream = s3Client.get(s3Id(image))
    Some(super.readSecondaryCaptureJpeg(s3InputStream, imageHeight))
  }

  def imageAsInputStream(image: Image): Option[InputStream] = {
    val s3InputStream = s3Client.get(s3Id(image))
    Some(s3InputStream)
  }

}
