package se.vgregion.db

import se.vgregion.dicom.scp.ScpProtocol.ScpData
import se.vgregion.dicom.MetaDataProtocol._
import se.vgregion.dicom.DicomHierarchy._
import se.vgregion.app.ApiUser

object DbProtocol {

  case object CreateTables
  
  case class InsertScpData(scpData: ScpData)
  
  case class InsertImageFile(imageFile: ImageFile)
  
  case class RemoveScpData(name: String)
 
  case class RemoveImage(image: Image)
  
  case object GetScpDataEntries
  
  case object GetImageFileEntries
  
  case object GetPatientEntries

  case class GetStudyEntries(patient: Patient)
  
  case class GetSeriesEntries(study: Study)
  
  case class GetImageEntries(series: Series)

  case class GetImageFileEntries(image: Image)

  case class GetUserByName(name: String)
  
  case object GetUserNames
  
  case class AddUser(user: ApiUser)

  case class DeleteUser(userName: String)

  case object UserAlreadyAdded

}
