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

  case class GetImportSession(id: Long)

  case class AddImportSession(importSession: ImportSession)

  case class AddImageToSession(importSession: ImportSession, image: Image, overwrite: Boolean)

  case class UpdateSessionWithRejection(importSession: ImportSession)

  case class ImageAddedToSession(importSessionImage: ImportSessionImage)

  case class GetImportSessions(startIndex: Long, count: Long)

  case class ImportSessions(importSessions:Seq[ImportSession])

  case class DeleteImportSession(id: Long)

  case class GetImportSessionImages(id: Long)

  case class ImportSessionImages(importSessionImages: Seq[ImportSessionImage])
}