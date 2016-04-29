/*
 * Copyright 2016 Lars Edenbrandt
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

import java.io.InputStream
import java.nio.file.Path

import org.dcm4che3.data.Attributes
import se.nimsa.sbx.dicom.DicomHierarchy.{FlatSeries, Image, Patient, Study}
import se.nimsa.sbx.app.GeneralProtocol._

object StorageProtocol {

  // domain objects

  case class FileName(value: String)

  case class ImageInformation(
    numberOfFrames: Int,
    frameIndex: Int,
    minimumPixelValue: Int,
    maximumPixelValue: Int)


  sealed trait ImageRequest

  case class GetImageData(image: Image) extends ImageRequest

  case class GetDataset(image: Image, withPixelData: Boolean, useBulkDataURI: Boolean = false) extends ImageRequest

  case class DatasetAdded(image: Image, overwrite: Boolean)

  case class GetImageAttributes(image: Image) extends ImageRequest

  case class GetImageInformation(image: Image) extends ImageRequest

  case class GetImageFrame(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int) extends ImageRequest

  case class CheckDataset(dataset: Attributes) extends ImageRequest

  case class AddDataset(dataset: Attributes, source: Source, image: Image) extends ImageRequest
  
  case class CreateJpeg(jpegBytes: Array[Byte], patient: Patient, study: Study) extends ImageRequest

  case class AddJpeg(image: Image, source: Source, jpegTempPath: Path) extends ImageRequest

  case class DeleteDataset(image: Image) extends ImageRequest

  case class CreateTempZipFile(imagesAndSeries: Seq[(Image, FlatSeries)]) extends ImageRequest


  case class JpegCreated(dataset: Attributes, jpegTempPath: Path)

  case object JpegAdded

  case class DatasetDeleted(image: Image)

  case class ImagePath(imagePath: Path)
  case class ImageData(data: Array[Byte])

  case class DeleteTempZipFile(path: Path)
}
