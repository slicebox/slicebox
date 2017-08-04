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

import java.io.FileNotFoundException
import java.nio.file.{Files, Path, StandardCopyOption}

import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import se.nimsa.sbx.lang.NotFoundException

import scala.concurrent.{ExecutionContext, Future}

/**
  * Service that stores DICOM files in local file system.
  *
  * @param path relative path to directory for DICOM files
  */
class FileStorage(val path: Path) extends StorageService {

  createStorageDirectoryIfNecessary()

  private def filePath(filePath: String): Path = path.resolve(filePath)

  override def move(sourceImageName: String, targetImageName: String): Unit =
    Files.move(path.resolve(sourceImageName), path.resolve(targetImageName), StandardCopyOption.REPLACE_EXISTING)

  override def deleteByName(names: Seq[String]): Unit = names.foreach(name => Files.delete(filePath(name)))

  private def createStorageDirectoryIfNecessary(): Unit = {
    if (!Files.exists(path))
      try
        Files.createDirectories(path)
      catch {
        case e: Exception => throw new RuntimeException("Dicom-files directory could not be created: " + e.getMessage)
      }
    if (!Files.isDirectory(path))
      throw new IllegalArgumentException("Dicom-files directory is not a directory.")
  }


  override def fileSink(name: String)(implicit executionContext: ExecutionContext): Sink[ByteString, Future[Done]] =
    FileIO.toPath(filePath(name)).mapMaterializedValue(_.map(_ => Done))

  override def fileSource(name: String): Source[ByteString, NotUsed] =
    FileIO.fromPath(filePath(name))
      .mapMaterializedValue(_ => NotUsed)
      .mapError {
        case _: FileNotFoundException => new NotFoundException(s"File not found for name $name")
      }

}
