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

import java.io.{ByteArrayOutputStream, InputStream}
import javax.imageio.ImageIO

import akka.actor.ActorSystem
import akka.stream.{ClosedShape, Materializer}
import akka.stream.alpakka.s3.scaladsl.S3Client
import akka.stream.scaladsl.{Broadcast, FileIO, GraphDSL, RunnableGraph, Sink, Source => StreamSource}
import akka.util.ByteString
import org.dcm4che3.data.Attributes
import se.nimsa.dcm4che.streams.DicomAttributesSink
import se.nimsa.dcm4che.streams.DicomFlows._
import se.nimsa.dcm4che.streams.DicomPartFlow._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.{Contexts, DicomData, DicomUtil, ImageAttribute}
import se.nimsa.sbx.storage.StorageProtocol.ImageInformation

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * Service that stores DICOM files on AWS S3.
  * @param s3Prefix prefix for keys
  * @param bucket S3 bucket
  */
class S3Storage(val bucket: String, val s3Prefix: String, val region: String) extends StorageService {

  val s3Client = new S3Facade(bucket, region)

  private def s3Id(image: Image): String =
    s3Id(imageName(image))

  private def s3Id(imageName: String): String =
    s3Prefix + "/" + imageName

  override def move(sourceImageName: String, targetImageName: String) = {
    // FIXME: prefix source ?
    s3Client.copy(sourceImageName, s3Id(targetImageName))
    s3Client.delete(sourceImageName)
  }

  override def storeDicomData(dicomData: DicomData, image: Image): Boolean = {
    val storedId = s3Id(image)
    val overwrite = s3Client.exists(storedId)
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
    s3Client.upload(s3Key, buffer)
  }

  override def deleteFromStorage(image: Image): Unit = s3Client.delete(s3Id(image))

  override def readDicomData(image: Image, withPixelData: Boolean): DicomData = {
    val s3InputStream = s3Client.get(s3Id(image))
    loadDicomData(s3InputStream, withPixelData)
  }

  override def readImageAttributes(image: Image): List[ImageAttribute] = {
    val s3InputStream = s3Client.get(s3Id(image))
    DicomUtil.readImageAttributes(loadDicomData(s3InputStream, withPixelData = false).attributes)
  }

  override def readImageInformation(image: Image): ImageInformation = {
    val s3InputStream = s3Client.get(s3Id(image))
    super.readImageInformation(s3InputStream)
  }

  override def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Array[Byte] = {
    val s3InputStream = s3Client.get(s3Id(image))
    val iis = ImageIO.createImageInputStream(s3InputStream)
    super.readPngImageData(iis, frameNumber, windowMin, windowMax, imageHeight)
  }

  override def imageAsInputStream(image: Image): InputStream = {
    val s3InputStream = s3Client.get(s3Id(image))
    s3InputStream
  }

  override def fileSink(tmpPath: String)(implicit actorSystem: ActorSystem, mat: Materializer):  Sink[ByteString, Future[Any]] = {
    // FIXME:  config!!!
    new S3Client(S3Facade.credentialsFromProviderChain(), "us-east-1").multipartUpload("dev-sbx-data.exiniaws.com", tmpPath)
  }


}
