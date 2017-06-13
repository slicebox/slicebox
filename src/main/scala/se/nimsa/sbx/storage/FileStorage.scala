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

  private def filePath(image: Image): Path =
    path.resolve(imageName(image))

  override def deleteFromStorage(image: Image): Unit =
    Files.delete(filePath(image))

  override def readDicomData(image: Image, withPixelData: Boolean): DicomData =
    loadDicomData(filePath(image), withPixelData)

  override def readImageAttributes(image: Image): List[ImageAttribute] =
    DicomUtil.readImageAttributes(loadDicomData(filePath(image), withPixelData = false).attributes)

  def readImageInformation(image: Image): ImageInformation =
    super.readImageInformation(new BufferedInputStream(Files.newInputStream(filePath(image))))

  override def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Array[Byte] = {
    val file = filePath(image).toFile
    val iis = ImageIO.createImageInputStream(file)
    super.readPngImageData(iis, frameNumber, windowMin, windowMax, imageHeight)
  }

  override def imageAsInputStream(image: Image): InputStream =
    new BufferedInputStream(Files.newInputStream(filePath(image)))

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
