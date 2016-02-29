package se.nimsa.sbx.importing

import se.nimsa.sbx.model.Entity

object ImportingProtocol {
  case class ImportSession(id: Long, name: String, filesImported: Int, filesRejected: Int, created: Long,
                           lastUpdated: Long) extends Entity
  case class ImportSessionImage(id: Long, importSessionId: Long) extends Entity
}