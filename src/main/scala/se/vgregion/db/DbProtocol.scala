package se.vgregion.db

import se.vgregion.dicom.ScpProtocol.ScpData

object DbProtocol {

  case class InsertScpData(scpData: ScpData)
  
  case class RemoveScpData(name: String)
  
  case object GetScpDataEntries
  
  case class MetaData(patientName: String, patientId: String, accessionNumber: String)
  
  case class InsertMetaData(metaData: MetaData)
  
  case object GetMetaDataEntries
  
}