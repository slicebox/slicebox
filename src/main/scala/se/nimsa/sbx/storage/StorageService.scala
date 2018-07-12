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

import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.LazyLogging
import se.nimsa.dicom.data.DicomParts.DicomPart
import se.nimsa.dicom.streams.ParseFlow

import scala.concurrent.{ExecutionContext, Future}


/**
  * Created by michaelkober on 2016-04-25.
  */
trait StorageService extends LazyLogging {

  val streamChunkSize = 5242880

  def imageName(imageId: Long): String = imageId.toString

  def deleteByName(name: Seq[String]): Unit

  def deleteFromStorage(imageIds: Seq[Long]): Unit = deleteByName(imageIds.map(imageName))

  def move(sourceImageName: String, targetImageName: String): Unit

  /** Sink for dicom files. */
  def fileSink(name: String)(implicit executionContext: ExecutionContext): Sink[ByteString, Future[Done]]

  /**
    * Source for dicom files.
    *
    * @throws NotFoundException if data cannot be found for imageId
    **/
  def fileSource(imageId: Long): Source[ByteString, NotUsed] = fileSource(imageName(imageId))

  /**
    * Flow for parsing binary dicom data
    *
    * @param stopTag optional stop tag (exclusive)
    */
  def parseFlow(stopTag: Option[Int]) = new ParseFlow(streamChunkSize, stopTag)

  /**
    * Source for dicom data
    *
    * @throws NotFoundException if data cannot be found for imageId
    */
  def dataSource(imageId: Long, stopTag: Option[Int]): Source[DicomPart, NotUsed] =
    fileSource(imageId).via(parseFlow(stopTag))

  /**
    * Source for dicom files.
    *
    * @throws NotFoundException if data cannot be found for name
    **/
  def fileSource(name: String): Source[ByteString, NotUsed]

}

