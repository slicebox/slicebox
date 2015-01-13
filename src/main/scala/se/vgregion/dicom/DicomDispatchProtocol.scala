package se.vgregion.dicom

import java.nio.file.Path
import org.dcm4che3.data.Attributes
import DicomHierarchy._

object DicomDispatchProtocol {

  // domain objects
  
  case class Owner(value: String) extends AnyVal

  case class ScpData(name: String, aeTitle: String, port: Int) 

  case class FileName(value: String) extends AnyVal

  case class ImageFile(image: Image, fileName: FileName, owner: Owner)


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


  sealed trait MetaDataRequest
  
  case class GetAllImages(owner: Option[Owner] = None) extends MetaDataRequest

  case class GetPatients(owner: Option[Owner] = None) extends MetaDataRequest

  case class GetStudies(patient: Patient, owner: Option[Owner] = None) extends MetaDataRequest

  case class GetSeries(study: Study, owner: Option[Owner] = None) extends MetaDataRequest

  case class GetImages(series: Series, owner: Option[Owner] = None) extends MetaDataRequest

  case class DeleteImage(image: Image, owner: Option[Owner] = None) extends MetaDataRequest

  case class DeleteSeries(series: Series, owner: Option[Owner] = None) extends MetaDataRequest

  case class DeleteStudy(study: Study, owner: Option[Owner] = None) extends MetaDataRequest

  case class DeletePatient(patient: Patient, owner: Option[Owner] = None) extends MetaDataRequest

  case class GetAllImageFiles(owner: Option[Owner] = None)

  case class GetImageFiles(image: Image, owner: Option[Owner] = None)

  // TODO case class AddDataset(dataset: Attributes, owner: Owner)

  case class ChangeOwner(image: Image, previousOwner: Owner, newOwner: Owner) extends MetaDataRequest

  // ***to API***

  case class Patients(patients: Seq[Patient]) 

  case class Studies(studies: Seq[Study]) 

  case class SeriesCollection(series: Seq[Series]) 

  case class Images(images: Seq[Image]) 

  case class ImagesDeleted(images: Seq[Image])

  // TODO case class ImageAdded(image: Image)

  case class OwnerChanged(image: Image, previousOwner: Owner, newOwner: Owner)

  case class DirectoryWatched(path: Path)

  case class DirectoryUnwatched(path: Path)

  case class ScpAdded(scpData: ScpData)

  case class ScpRemoved(scpData: ScpData)

  case class ScpNotFound(scpData: ScpData)


  // ***from scp***

  case class DatasetReceivedByScp(metaInformation: Attributes, dataset: Attributes)

  // ***from direcory watch***

  case class FileAddedToWatchedDirectory(filePath: Path)

  case class FileRemovedFromWatchedDirectory(filePath: Path)

  // ***to metadata***

  case class AddDataset(metaInformation: Attributes, dataset: Attributes, fileName: String, owner: String)

  // ***from metadata***

  case class ImageFiles(imageFiles: Seq[ImageFile])
  
  case class DatasetAdded(imageFile: ImageFile)

  case class DatasetNotAdded(reason: String)

  case class ImageFilesDeleted(imageFiles: Seq[ImageFile])

  case class ImageDeleted(image: Image)
  
  case class SeriesDeleted(series: Series)
  
  case class StudyDeleted(study: Study)
  
  case class PatientDeleted(patient: Patient)
  
  // ***to storage***

  case class StoreFile(filePath: Path)

  case class StoreDataset(metaInformation: Attributes, dataset: Attributes)

  case class DeleteFile(filePath: Path)

  // ***from storage***

  case class FileStored(filePath: Path, metaInformation: Attributes, dataset: Attributes)

  case class FileNotStored(reason: String)

  case class FileDeleted(filePath: Path)

  case class FileNotDeleted(reason: String)

}