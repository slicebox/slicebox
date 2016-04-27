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

  case class AddImageToSession(importSession: ImportSession, image: Image, overwrite: Boolean) extends ImportSessionRequest

  case class UpdateSessionWithRejection(importSession: ImportSession) extends ImportSessionRequest

  case object GetImportSessions extends ImportSessionRequest

  case class DeleteImportSession(id: Long) extends ImportSessionRequest

  case class GetImportSessionImages(id: Long) extends ImportSessionRequest

  case class ImageAddedToSession(importSessionImage: ImportSessionImage)

  case class ImportSessions(importSessions:Seq[ImportSession])

  case class ImportSessionImages(importSessionImages: Seq[ImportSessionImage])
}