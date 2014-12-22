package se.vgregion.dicom

import java.nio.file.Path
import org.dcm4che3.data.Attributes
import DicomHierarchy._
import DicomMetaDataProtocol.ImageFile
import se.vgregion.app._
import se.vgregion.util.RestMessage

object DicomDispatchProtocol {

  case class Owner(value: String) extends AnyVal

  // Rest messages

  case object Initialize extends RestMessage

  case class WatchDirectory(pathString: String) extends RestMessage

  case class UnWatchDirectory(pathString: String) extends RestMessage

  case class ScpData(name: String, aeTitle: String, port: Int) extends RestMessage

  case class AddScp(scpData: ScpData) extends RestMessage

  case class RemoveScp(scpData: ScpData) extends RestMessage

  case object GetScpDataCollection extends RestMessage

  case class ScpDataCollection(scpDataCollection: Seq[ScpData]) extends RestMessage

  case class GetAllImages(owner: Option[Owner] = None) extends RestMessage

  case class GetPatients(owner: Option[Owner] = None) extends RestMessage

  case class GetStudies(patient: Patient, owner: Option[Owner] = None) extends RestMessage

  case class GetSeries(study: Study, owner: Option[Owner] = None) extends RestMessage

  case class GetImages(series: Series, owner: Option[Owner] = None) extends RestMessage

  case class DeleteImage(image: Image, owner: Option[Owner] = None) extends RestMessage

  case class DeleteSeries(series: Series, owner: Option[Owner] = None) extends RestMessage

  case class DeleteStudy(study: Study, owner: Option[Owner] = None) extends RestMessage

  case class DeletePatient(patient: Patient, owner: Option[Owner] = None) extends RestMessage

  // TODO case class AddDataset(dataset: Attributes, owner: Owner)

  case class ChangeOwner(image: Image, newOwner: Owner) extends RestMessage

  // ***to API***

  case object Initialized

  case object InitializationFailed

  case class Patients(patients: Seq[Patient]) extends RestMessage

  case class Studies(studies: Seq[Study]) extends RestMessage

  case class SeriesCollection(series: Seq[Series]) extends RestMessage

  case class Images(images: Seq[Image]) extends RestMessage

  case class ImagesDeleted(images: Seq[Image])

  // TODO case class ImageAdded(image: Image)

  case class OwnerChanged(image: Image, previousOwner: Owner, newOwner: Owner)

  case class DirectoryWatched(path: Path)

  case class DirectoryUnwatched(path: Path)

  case class DirectoryWatchFailed(reason: String)

  case class ScpAdded(scpData: ScpData)

  case class ScpRemoved(scpData: ScpData)

  case class ScpNotFound(scpData: ScpData)

  case class ScpSetupFailed(reason: String)
  
  // ***to scp***

  // Reused: AddScp, RemoveScp

  // ***from scp***

  // Reused: ScpAdded, ScpRemoved

  case class ScpAlreadyAdded(scpData: ScpData)

  case class DatasetReceivedByScp(metaInformation: Attributes, dataset: Attributes)

  // ***to directory watch***

  // Reused: WatchDirectory, UnwatchedDirectory

  // ***from direcory watch***

  // Reused: DirectoryWatched, DirectoryUnwatched

  case class FileAddedToWatchedDirectory(filePath: Path)

  // ***to metadata***

  // Resued: GetImages, GetPatients, GetStudies, GetSeries, GetImages, DeleteImage, DeleteSeries, DeleteStudy, DeletePatient, AddImage, ChangeOwner

  case class FileName(value: String) extends AnyVal

  case class AddDataset(metaInformation: Attributes, dataset: Attributes, fileName: String, owner: String)

  // ***from metadata***

  // Reused: Patients, Studies, SeriesCollection, Images, ImageFiles, ImageAdded, OwnerChanged

  case class DatasetAdded(imageFile: ImageFile)

  case class DatasetNotAdded(reason: String)

  case class ImageFilesDeleted(imageFiles: Seq[ImageFile])

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