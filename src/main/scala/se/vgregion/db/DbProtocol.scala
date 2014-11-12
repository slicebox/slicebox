package se.vgregion.db

import se.vgregion.dicom.ScpProtocol.ScpData

object DbProtocol {

  case class InsertStoreScpData(scpData: ScpData)
  
  case object GetStoreScpDataEntries
  
  case class MetaData(patientName: String, patientId: String, accessionNumber: String)
  
  case class InsertMetaData(metaData: MetaData)
  
  case object GetMetaDataEntries
  
}