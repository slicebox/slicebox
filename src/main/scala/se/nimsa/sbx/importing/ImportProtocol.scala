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
    filesRejected: Int, 
    created: Long,
    lastUpdated: Long) extends Entity
    
  case class ImportSessionImage(id: Long, importSessionId: Long, imageId: Long) extends Entity

  case class GetImportSession(id: Long)

  case class AddImageToSession(importSessionId: Long, image: Image)
  
  case class ImageAddedToSession(importSessionImage: ImportSessionImage)
  
}