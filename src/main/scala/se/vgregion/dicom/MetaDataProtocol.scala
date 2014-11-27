package se.vgregion.dicom

import java.nio.file.Path
import spray.json.DefaultJsonProtocol

object MetaDataProtocol {

  case class Patient(
      patientName: String,
      patientID: String)

  case class Study(
      patient: Patient,
      studyDate: String,
      studyInstanceUID: String)
      
  case class Series(
      study: Study,
      seriesDate: String,
      seriesInstanceUID: String)
      
  case class Image(
      series: Series,
      sopInstanceUID: String,
      fileName: String)
      
  // incoming

  case class AddImage(path: Path)

  case class DeleteImage(path: Path)

  case object GetImages

  case object GetPatients
  
  case class GetStudies(patient: Patient)
  
  case class GetSeries(study: Study)

  case class GetImages(series: Series)

  // outgoing

  case class Patients(patients: Seq[Patient])

  case class Studies(studies: Seq[Study])
  
  case class SeriesCollection(series: Seq[Series])
  
  case class Images(images: Seq[Image])
  
  //----------------------------------------------
  // JSON
  //----------------------------------------------

  object Patient extends DefaultJsonProtocol {
    implicit val format = DefaultJsonProtocol.jsonFormat2(Patient.apply)
  }

  object Patients extends DefaultJsonProtocol {
    implicit val format = DefaultJsonProtocol.jsonFormat1(Patients.apply)
  }

  object Study extends DefaultJsonProtocol {
    implicit val format = DefaultJsonProtocol.jsonFormat3(Study.apply)
  }

  object Studies extends DefaultJsonProtocol {
    implicit val format = DefaultJsonProtocol.jsonFormat1(Studies.apply)
  }

  object Series extends DefaultJsonProtocol {
    implicit val format = DefaultJsonProtocol.jsonFormat3(Series.apply)
  }

  object SeriesCollection extends DefaultJsonProtocol {
    implicit val format = DefaultJsonProtocol.jsonFormat1(SeriesCollection.apply)
  }

  object Image extends DefaultJsonProtocol {
    implicit val format = DefaultJsonProtocol.jsonFormat3(Image.apply)
  }

  object Images extends DefaultJsonProtocol {
    implicit val format = DefaultJsonProtocol.jsonFormat1(Images.apply)
  }

}