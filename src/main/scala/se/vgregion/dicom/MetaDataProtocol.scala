package se.vgregion.dicom

import spray.json.DefaultJsonProtocol
import org.dcm4che3.data.Attributes

object MetaDataProtocol {
  import DicomPropertyValue._
  import DicomHierarchy._

  case class FileName(value: String) extends AnyVal
  case class Owner(value: String) extends AnyVal

  case class ImageFile(
    image: Image,
    fileName: FileName,
    owner: Owner)

  // incoming

  case class AddDataset(dataset: Attributes, fileName: String, owner: String)

  case class DeleteDataset(dataset: Attributes)

  case object GetImages

  case object GetPatients

  case class GetStudies(patient: Patient)

  case class GetSeries(study: Study)

  case class GetImages(series: Series)

  case class GetImageFiles(image: Image)

  // outgoing

  case class Patients(patients: Seq[Patient])

  case class Studies(studies: Seq[Study])

  case class SeriesCollection(series: Seq[Series])

  case class Images(images: Seq[Image])

  case class ImageFiles(imageFiles: Seq[ImageFile])

  object FileName extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(FileName.apply) }
  object Owner extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(Owner.apply) }
  object Patients extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(Patients.apply) }
  object Studies extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(Studies.apply) }
  object SeriesCollection extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(SeriesCollection.apply) }
  object Images extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(Images.apply) }
  object ImageFile extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat3(ImageFile.apply) }
  object ImageFiles extends DefaultJsonProtocol { implicit val format = DefaultJsonProtocol.jsonFormat1(ImageFiles.apply) }

}