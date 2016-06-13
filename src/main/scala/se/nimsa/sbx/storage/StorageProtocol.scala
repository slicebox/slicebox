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

import java.nio.file.Path

import org.dcm4che3.data.Attributes
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.DicomData

object StorageProtocol {

  // domain objects

  case class ImageInformation(
    numberOfFrames: Int,
    frameIndex: Int,
    minimumPixelValue: Int,
    maximumPixelValue: Int)


  sealed trait ImageRequest

  case class GetImageData(image: Image) extends ImageRequest

  case class GetDataset(image: Image, withPixelData: Boolean, useBulkDataURI: Boolean = false) extends ImageRequest

  case class GetImageAttributes(image: Image) extends ImageRequest

  case class GetImageInformation(image: Image) extends ImageRequest

  case class GetPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int) extends ImageRequest

  case class CheckDataset(dicomData: DicomData, useExtendedContexts: Boolean) extends ImageRequest

  case class AddDataset(dicomData: DicomData, source: Source, image: Image) extends ImageRequest
  
  case class DeleteDataset(image: Image) extends ImageRequest

  case class CreateExportSet(imageIds: Seq[Long]) extends ImageRequest

  case class GetExportSetImageIds(exportSetId: Long) extends ImageRequest


  case class ImageData(data: Array[Byte])

  case class PngImageData(data: Array[Byte])

  case object PngImageDataNotAvailable

  case class DatasetAdded(image: Image, overwrite: Boolean)

  case class DatasetDeleted(image: Image)

  case class ExportSetId(id: Long)

}
