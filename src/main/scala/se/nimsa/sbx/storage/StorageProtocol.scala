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

object StorageProtocol {

  // domain objects

  case class FileName(value: String)

  case class ImageInformation(
    numberOfFrames: Int,
    frameIndex: Int,
    minimumPixelValue: Int,
    maximumPixelValue: Int)

  // messages

  sealed trait ImageRequest

  case class GetImagePath(imageId: Long) extends ImageRequest

  case class GetDataset(imageId: Long, withPixelData: Boolean) extends ImageRequest

  case class GetImageAttributes(imageId: Long) extends ImageRequest

  case class GetImageInformation(imageId: Long) extends ImageRequest

  case class GetImageFrame(imageId: Long, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int) extends ImageRequest

  case class AddDataset(dataset: Attributes, source: Source)
  
  case class AddJpeg(jpegBytes: Array[Byte], studyId: Long, source: Source)
  
  case class DeleteDataset(imageId: Long)

  case class CreateTempZipFile(imageIds: Seq[Long])
  
  case class DeleteTempZipFile(path: Path)
  
  // ***to API***

  case class DatasetAdded(image: Image, source: Source, overwrite: Boolean)
  
  case class DatasetDeleted(imageId: Long)

  case class ImagePath(imagePath: Path)

  // ***to storage***

  case class DatasetReceived(dataset: Attributes, source: Source)

  case class FileReceived(path: Path, source: Source)

}
