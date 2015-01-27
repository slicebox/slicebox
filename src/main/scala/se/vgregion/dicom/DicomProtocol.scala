package se.vgregion.dicom

import java.nio.file.Path
import org.dcm4che3.data.Attributes
import DicomHierarchy._

object DicomProtocol {

  // domain objects
  
  case class ScpData(name: String, aeTitle: String, port: Int) 

  case class FileName(value: String)

  case class ImageFile(image: Image, fileName: FileName)


  // messages

    
  sealed trait DirectoryRequest
  
  case class WatchDirectory(pathString: String) extends DirectoryRequest

  case class UnWatchDirectory(pathString: String) extends DirectoryRequest

  case object GetWatchedDirectories extends DirectoryRequest
    
  case class WatchedDirectories(names: Seq[Path])

  
  
  sealed trait ScpRequest
  
  case class AddScp(scpData: ScpData) extends ScpRequest

  case class RemoveScp(scpData: ScpData) extends ScpRequest 

  case object GetScpDataCollection extends ScpRequest 

  case class ScpDataCollection(scpDataCollection: Seq[ScpData]) 


  sealed trait MetaDataQuery
  
  case object GetAllImages extends MetaDataQuery

  case object GetPatients extends MetaDataQuery

  case class GetStudies(patient: Patient) extends MetaDataQuery

  case class GetSeries(study: Study) extends MetaDataQuery

  case class GetImages(series: Series) extends MetaDataQuery

  
  sealed trait MetaDataUpdate
  
  case class DeleteImage(image: Image) extends MetaDataUpdate

  case class DeleteSeries(series: Series) extends MetaDataUpdate

  case class DeleteStudy(study: Study) extends MetaDataUpdate

  case class DeletePatient(patient: Patient) extends MetaDataUpdate
  
  
  case object GetAllImageFiles

  case class GetImageFiles(image: Image)

  case class AddDataset(dataset: Attributes)

  // ***to API***

  case class Patients(patients: Seq[Patient]) 

  case class Studies(studies: Seq[Study]) 

  case class SeriesCollection(series: Seq[Series]) 

  case class Images(images: Seq[Image]) 

  case class ImagesDeleted(images: Seq[Image])

  case class ImageAdded(image: Image)

  case class DirectoryWatched(path: Path)

  case class DirectoryUnwatched(path: Path)

  case class ScpAdded(scpData: ScpData)

  case class ScpRemoved(scpData: ScpData)


  // ***from scp***

  case class DatasetReceivedByScp(dataset: Attributes)

  // ***from direcory watch***

  case class FileAddedToWatchedDirectory(filePath: Path)

  // ***to storage***

  case class DatasetReceived(dataset: Attributes)
  
  // ***from storage***

  case class ImageFiles(imageFiles: Seq[ImageFile])
  
  case class DatasetAdded(imageFile: ImageFile)

  case class ImageFilesDeleted(imageFiles: Seq[ImageFile])

}