package se.vgregion.db

import se.vgregion.dicom.ScpProtocol.ScpData
import se.vgregion.dicom.MetaDataProtocol._

object DbProtocol {

  case object CreateTables
  
  case class InsertScpData(scpData: ScpData)
  
  case class InsertImage(image: Image)
  
  case class RemoveScpData(name: String)
 
  case class RemoveImage(fileName: String)
  
  case object GetScpDataEntries
  
  case object GetImageEntries
  
  case object GetPatientEntries

  case class GetStudyEntries(patient: Patient)
  
  case class GetSeriesEntries(study: Study)
  
  case class GetImageEntries(series: Series)
}