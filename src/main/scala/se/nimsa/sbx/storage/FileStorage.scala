/*
 * Copyright 2017 Lars Edenbrandt
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
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import se.nimsa.sbx.dicom.DicomData
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._

import scala.concurrent.{ExecutionContext, Future}
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

  private def filePath(filePath: String): Path =
    path.resolve(filePath)

  override def move(sourceImageName: String, targetImageName: String): Unit =
    Files.move(path.resolve(sourceImageName), path.resolve(targetImageName), StandardCopyOption.REPLACE_EXISTING)

  override def deleteFromStorage(name: String): Unit =
    Files.delete(filePath(name))

  override def deleteFromStorage(image: Image): Unit =
    Files.delete(filePath(image))

  override def readDicomData(image: Image, withPixelData: Boolean): DicomData =
    loadDicomData(filePath(image), withPixelData)

  override def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int)
                               (implicit system: ActorSystem, materializer: Materializer): Array[Byte] = {
    val path = filePath(image)
    val source = FileIO.fromPath(path)
    super.readPngImageData(source, frameNumber, windowMin, windowMax, imageHeight)
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


  override def fileSink(name: String)(implicit actorSystem: ActorSystem, mat: Materializer, ec: ExecutionContext):  Sink[ByteString, Future[Done]] =
    FileIO.toPath(filePath(name)).mapMaterializedValue(_.map(_ => Done))

  override def fileSource(image: Image)(implicit actorSystem: ActorSystem, mat: Materializer): Source[ByteString, NotUsed] =
    FileIO.fromPath(filePath(image)).mapMaterializedValue(_ => NotUsed)

}
