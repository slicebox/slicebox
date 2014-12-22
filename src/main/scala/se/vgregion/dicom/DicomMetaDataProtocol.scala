package se.vgregion.dicom

object DicomMetaDataProtocol {
  import DicomPropertyValue._
  import DicomHierarchy._
  import DicomDispatchProtocol._

  case class ImageFile(
    image: Image,
    fileName: FileName,
    owner: Owner)

  case class AddImageFile(imageFile: ImageFile)

  case class ImageFileAdded(imageFile: ImageFile)
  
  case class ImageDeleted(image: Image)
  
  case class SeriesDeleted(series: Series)
  
  case class StudyDeleted(study: Study)
  
  case class PatientDeleted(patient: Patient)
  
  case class GetAllImageFiles(owner: Option[Owner] = None)

  case class GetImageFiles(image: Image, owner: Option[Owner] = None)

  // outgoing

  case class ImageFiles(imageFiles: Seq[ImageFile])

}