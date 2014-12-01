package se.vgregion.db

import se.vgregion.dicom.ScpProtocol.ScpData
import se.vgregion.dicom.MetaDataProtocol._

object DbProtocol {

  case object CreateTables
  
  case class InsertScpData(scpData: ScpData)
  
  case class InsertImageFile(imageFile: ImageFile)
  
  case class RemoveScpData(name: String)
 
  case class RemoveImageFile(fileName: String)
  
  case object GetScpDataEntries
  
  case object GetImageFileEntries
  
  case object GetPatientEntries

  case class GetStudyEntries(patient: Patient)
  
  case class GetSeriesEntries(study: Study)
  
  case class GetImageEntries(series: Series)

  case class GetImageFileEntries(image: Image)

}