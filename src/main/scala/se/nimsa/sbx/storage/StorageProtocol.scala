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

import akka.util.ByteString
import se.nimsa.dcm4che.streams.TagPath.TagPathTag

object StorageProtocol {

  // domain objects

  case class ImageInformation(
    numberOfFrames: Int,
    frameIndex: Int,
    minimumPixelValue: Int,
    maximumPixelValue: Int)

  case class TagMapping(tagPath: TagPathTag, value: ByteString)

  sealed trait ImageRequest

  case class CreateExportSet(imageIds: Seq[Long]) extends ImageRequest

  case class GetExportSetImageIds(exportSetId: Long) extends ImageRequest

  case class ExportSetId(id: Long)

}
