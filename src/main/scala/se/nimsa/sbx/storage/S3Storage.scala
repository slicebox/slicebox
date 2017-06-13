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

import java.io.{ByteArrayOutputStream, InputStream}

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.alpakka.s3.scaladsl.S3Client
import akka.stream.alpakka.s3.{MemoryBufferType, S3Settings}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import se.nimsa.sbx.dicom.DicomData
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * Service that stores DICOM files on AWS S3.
  *
  * @param s3Prefix prefix for keys
  * @param bucket   S3 bucket
  * @param region   aws region of the bucket
  */
class S3Storage(val bucket: String, val s3Prefix: String, val region: String)(implicit system: ActorSystem, materializer: Materializer) extends StorageService {

  private val settings = new S3Settings(
    bufferType = MemoryBufferType,
    diskBufferPath = "",
    proxy = None,
    awsCredentials = S3Facade.credentialsFromProviderChain(),
    s3Region = region,
    pathStyleAccess = false
  )

  private val s3Client = new S3Client(settings)
  private val s3Facade = new S3Facade(bucket, region)

  private def s3Id(image: Image): String =
    s3Id(imageName(image))

  private def s3Id(imageName: String): String =
    s3Prefix + "/" + imageName

  override def move(sourceImageName: String, targetImageName: String) = {
    s3Facade.copy(sourceImageName, s3Id(targetImageName))
    s3Facade.delete(sourceImageName)
  }

  override def storeDicomData(dicomData: DicomData, image: Image): Boolean = {
    val storedId = s3Id(image)
    val overwrite = s3Facade.exists(storedId)
    try saveDicomDataToS3(dicomData, storedId) catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException("Dicom data could not be stored", e)
    }
    overwrite
  }

  private def saveDicomDataToS3(dicomData: DicomData, s3Key: String): Unit = {
    val os = new ByteArrayOutputStream()
    try saveDicomData(dicomData, os) catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException("Dicom data could not be stored", e)
    }
    val buffer = os.toByteArray
    s3Facade.upload(s3Key, buffer)
  }

  override def deleteFromStorage(name: String): Unit = s3Facade.delete(s3Id(name))

  override def deleteFromStorage(image: Image): Unit = deleteFromStorage(s3Id(image))

  override def readDicomData(image: Image, withPixelData: Boolean): DicomData = {
    val s3InputStream = s3Facade.get(s3Id(image))
    loadDicomData(s3InputStream, withPixelData)
  }

  override def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int)(implicit materializer: Materializer): Array[Byte] = {
    super.readPngImageData(fileSource(image), frameNumber, windowMin, windowMax, imageHeight)
  }

  override def imageAsInputStream(image: Image): InputStream = {
    val s3InputStream = s3Facade.get(s3Id(image))
    s3InputStream
  }

  override def fileSink(name: String)(implicit executionContext: ExecutionContext): Sink[ByteString, Future[Done]] = {
    s3Client.multipartUpload(bucket, name).mapMaterializedValue(_.map(_ => Done))
  }

  override def fileSource(image: Image): Source[ByteString, NotUsed] = {
    s3Client.download(bucket, s3Id(image))
  }
}
