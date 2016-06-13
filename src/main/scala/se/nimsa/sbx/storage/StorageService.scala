package se.nimsa.sbx.storage

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, InputStream}
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

import com.amazonaws.util.IOUtils
import org.dcm4che3.data.{BulkData, Fragments, Tag}
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.{DicomData, ImageAttribute}
import se.nimsa.sbx.storage.StorageProtocol.ImageInformation

/**
  * Created by michaelkober on 2016-04-25.
  */
trait StorageService {

  val bufferSize = 524288

  def storeDataset(dicomData: DicomData, image: Image): Boolean

  def storeEncapsulated(image: Image, dcmTempPath: Path): Unit

  def imageName(image: Image) = image.id.toString

  def deleteFromStorage(images: Seq[Image]): Unit = images foreach (deleteFromStorage(_))

  def deleteFromStorage(image: Image): Unit

  def readDataset(image: Image, withPixelData: Boolean, useBulkDataURI: Boolean): Option[DicomData]

  def readImageAttributes(image: Image): Option[List[ImageAttribute]]

  def readImageInformation(image: Image): Option[ImageInformation]

  def readImageInformation(inputStream: InputStream): ImageInformation = {
    val dicomData = loadDataset(inputStream, withPixelData = false, useBulkDataURI = false)
    val attributes = dicomData.attributes
    val instanceNumber = attributes.getInt(Tag.InstanceNumber, 1)
    val imageIndex = attributes.getInt(Tag.ImageIndex, 1)
    val frameIndex = if (instanceNumber > imageIndex) instanceNumber else imageIndex
    ImageInformation(
      attributes.getInt(Tag.NumberOfFrames, 1),
      frameIndex,
      attributes.getInt(Tag.SmallestImagePixelValue, 0),
      attributes.getInt(Tag.LargestImagePixelValue, 0))
  }

  def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Option[Array[Byte]]

  def readPngImageData(iis: ImageInputStream, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Array[Byte] = {
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

  def imageAsInputStream(image: Image): Option[InputStream]

  def imageAsByteArray(image: Image): Option[Array[Byte]] = {
    imageAsInputStream(image) match {
      case None => None
      case Some(is: InputStream) =>
        try {
          val bytes = IOUtils.toByteArray(is)
          Some(bytes)
        } catch {
          case e: IOException =>
            None
        } finally {
          IOUtils.closeQuietly(is, null)
        }
    }
  }

  def readSecondaryCaptureJpeg(image: Image, imageHeight: Int): Option[Array[Byte]]

  def readSecondaryCaptureJpeg(inputStream: InputStream, imageHeight: Int) = {
    val ds = loadJpegDataset(inputStream)
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

}
