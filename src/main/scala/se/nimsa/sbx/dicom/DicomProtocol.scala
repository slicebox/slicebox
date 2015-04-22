package se.nimsa.sbx.dicom

import java.nio.file.Path
import org.dcm4che3.data.Attributes
import DicomHierarchy._

object DicomProtocol {

  import se.nimsa.sbx.model.Entity
  
  // domain objects
  
  case class ScpData(id: Long, name: String, aeTitle: String, port: Int) extends Entity

  case class ScuData(id: Long, name: String, aeTitle: String, host: String, port: Int) extends Entity

  case class FileName(value: String)

  case class ImageFile(
    id: Long,
    fileName: FileName) extends Entity {
    
    override def equals(o: Any): Boolean = o match {
      case that: ImageFile => that.fileName == fileName
      case _ => false
    }
  }
  
  case class WatchedDirectory(id: Long, path: String) extends Entity

  case class ImageAttribute(
      group: String, 
      element: String, 
      vr: String, 
      length: Int, 
      multiplicity: Int, 
      depth: Int, 
      path: String, 
      name: String, 
      value: String)
  
  case class ImageInformation(
      numberOfFrames: Int,
      frameIndex: Int,
      minimumPixelValue: Int,
      maximumPixelValue: Int)
      
  // messages

    
  sealed trait DirectoryRequest
  
  case class WatchDirectory(pathString: String) extends DirectoryRequest

  case class UnWatchDirectory(id: Long) extends DirectoryRequest

  case object GetWatchedDirectories extends DirectoryRequest
    
  case class WatchedDirectories(directories: Seq[WatchedDirectory])

  
  
  sealed trait ScpRequest
  
  case class AddScp(name: String, aeTitle: String, port: Int) extends ScpRequest

  case class RemoveScp(id: Long) extends ScpRequest 

  case object GetScps extends ScpRequest 

  case class Scps(scps: Seq[ScpData]) 


  sealed trait ScuRequest
  
  case class AddScu(name: String, aeTitle: String, host: String, port: Int) extends ScuRequest

  case class RemoveScu(id: Long) extends ScuRequest 

  case object GetScus extends ScuRequest 

  case class SendSeriesToScp(seriesId: Long, scuId: Long) extends ScuRequest
  
  case class Scus(scps: Seq[ScuData]) 

  sealed trait MetaDataQuery

  case class GetPatients(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]) extends MetaDataQuery

  case class GetStudies(startIndex: Long, count: Long, patientId: Long) extends MetaDataQuery

  case class GetSeries(startIndex: Long, count: Long, studyId: Long) extends MetaDataQuery

  case class GetFlatSeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]) extends MetaDataQuery

  case class GetImages(seriesId: Long) extends MetaDataQuery
  
  case class GetImageFile(imageId: Long) extends MetaDataQuery
  
  case class GetPatient(patientId: Long) extends MetaDataQuery
  
  case class GetStudy(studyId: Long) extends MetaDataQuery
  
  case class GetSingleSeries(seriesId: Long) extends MetaDataQuery
  
  
  sealed trait MetaDataUpdate
  
  case class DeleteImage(imageId: Long) extends MetaDataUpdate

  case class DeleteSeries(seriesId: Long) extends MetaDataUpdate

  case class DeleteStudy(studyId: Long) extends MetaDataUpdate

  case class DeletePatient(patientId: Long) extends MetaDataUpdate
  
  
  sealed trait ImageRequest
  
  case class GetImageAttributes(imageId: Long) extends ImageRequest
  
  case class GetImageInformation(imageId: Long) extends ImageRequest
  
  case class GetImageFrame(imageId: Long, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int) extends ImageRequest
  
  
  case class AddDataset(dataset: Attributes)
  
  // ***to API***

  case class Patients(patients: Seq[Patient]) 

  case class Studies(studies: Seq[Study]) 

  case class SeriesCollection(series: Seq[Series]) 

  case class FlatSeriesCollection(series: Seq[FlatSeries]) 

  case class Images(images: Seq[Image]) 

  case class ImagesDeleted(images: Seq[Image])

  case class ImageAdded(image: Image)

  case class DirectoryUnwatched(id: Long)

  case class ScpRemoved(scpDataId: Long)

  case class ScuRemoved(scuDataId: Long)

  case class ImagesSentToScp(scuId: Long, imageIds: Seq[Long])
  

  // ***from scp***

  case class DatasetReceivedByScp(dataset: Attributes)

  // ***from direcory watch***

  case class FileAddedToWatchedDirectory(filePath: Path)

  // ***to storage***

  case class DatasetReceived(dataset: Attributes)
  
  case class FileReceived(path: Path)
  
  // ***from storage***

  case class ImageFiles(imageFiles: Seq[ImageFile])
    
  case class ImageAttributes(attributes: Seq[ImageAttribute])

  case class ImageFrame(bytes: Array[Byte])
  
  case class ImageFilesDeleted(imageFiles: Seq[ImageFile])
  
  // Series
  
  case class SeriesDataset(id: Long, url: String)

}