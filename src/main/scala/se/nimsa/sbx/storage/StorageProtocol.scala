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

  case class GetDicomData(image: Image, withPixelData: Boolean) extends ImageRequest

  case class CheckDicomData(dicomData: DicomData, useExtendedContexts: Boolean) extends ImageRequest

  case class AddDicomData(dicomData: DicomData, source: Source, image: Image) extends ImageRequest
  
  case class DeleteDicomData(image: Image) extends ImageRequest

  case class MoveDicomData(sourceImageName: String, targetImageName: String) extends ImageRequest

  case class CreateExportSet(imageIds: Seq[Long]) extends ImageRequest

  case class GetExportSetImageIds(exportSetId: Long) extends ImageRequest


  case class DicomDataArray(data: Array[Byte])

  case class DicomDataAdded(image: Image, overwrite: Boolean)

  case class DicomDataDeleted(image: Image)

  case class DicomDataMoved(sourceImageName: String, targetImageName: String)

  case class ExportSetId(id: Long)

}
