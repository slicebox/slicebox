package se.nimsa.sbx.importing

import se.nimsa.sbx.model.Entity

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
  
}