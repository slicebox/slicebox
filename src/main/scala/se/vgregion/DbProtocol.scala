package se.vgregion

object DbProtocol {

  case class MetaData(patientName: String, patientId: String, accessionNumber: String)
  
  case class InsertMetaData(metaData: MetaData)
  
  case object GetMetaDataEntries
  
}