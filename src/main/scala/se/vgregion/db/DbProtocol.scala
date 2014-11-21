package se.vgregion.db

import se.vgregion.dicom.ScpProtocol.ScpData
import se.vgregion.dicom.MetaDataProtocol.MetaData

object DbProtocol {

  case object CreateTables
  
  case class InsertScpData(scpData: ScpData)
  
  case class InsertMetaData(metaData: MetaData)
  
  case class RemoveScpData(name: String)
 
  case class RemoveMetaData(fileName: String)
  
  case object GetScpDataEntries
  
  case object GetMetaDataEntries
  
  case object GetPatientEntries
  
}