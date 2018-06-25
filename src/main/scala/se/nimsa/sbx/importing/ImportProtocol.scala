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

package se.nimsa.sbx.importing

import se.nimsa.sbx.model.Entity
import se.nimsa.sbx.dicom.DicomHierarchy.Image

object ImportProtocol {

  case class ImportSession(
    id: Long,
    name: String,
    userId: Long,
    user: String,
    filesImported: Int,
    filesAdded: Int,
    filesRejected: Int, 
    created: Long,
    lastUpdated: Long) extends Entity

  case class ImportSessionImage(id: Long, importSessionId: Long, imageId: Long) extends Entity


  sealed trait ImportSessionRequest

  case class GetImportSession(id: Long) extends ImportSessionRequest

  case class AddImportSession(importSession: ImportSession) extends ImportSessionRequest

  case class AddImageToSession(importSessionId: Long, image: Image, overwrite: Boolean) extends ImportSessionRequest

  case class UpdateSessionWithRejection(importSessionId: Long) extends ImportSessionRequest

  case class GetImportSessions(startIndex: Long, count: Long) extends ImportSessionRequest

  case class DeleteImportSession(id: Long) extends ImportSessionRequest

  case class GetImportSessionImages(id: Long) extends ImportSessionRequest


  case class ImageAddedToSession(importSessionImage: ImportSessionImage)

  case class ImportSessions(importSessions:Seq[ImportSession])

  case class ImportSessionImages(importSessionImages: Seq[ImportSessionImage])
}
