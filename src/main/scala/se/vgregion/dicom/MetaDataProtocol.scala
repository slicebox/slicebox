package se.vgregion.dicom

import java.nio.file.Path
import spray.json.DefaultJsonProtocol

object MetaDataProtocol {

  case class MetaData(
    patientName: String,
    patientID: String,
    studyInstanceUID: String,
    studyDate: String,
    seriesInstanceUID: String,
    seriesDate: String,
    sopInstanceUID: String,
    fileName: String)

  case class Patient(
      patientName: String,
      patientID: String)

  case class Study(
      studyInstanceUUID: String,
      studyDate: String)
      
  case class Series(
      seriesInstanceUUID: String,
      seriesDate: String)
      
  case class FileName(
      fileName: String)
      
  // incoming

  case class AddMetaData(path: Path)

  case class DeleteMetaData(path: Path)

  case object GetMetaDataCollection

  case object GetPatients
  
  case class GetStudies(patient: Patient)
  
  case class GetSeries(patient: Patient, study: Study)

  case class GetFileNames(patient: Patient, study: Study, series: Series)
    
  // outgoing

  case class MetaDataCollection(metaDataCollection: Seq[MetaData])

  case class Patients(patients: Seq[Patient])

  case class Studies(studies: Seq[Study])
  
  case class SeriesCollection(series: Seq[Series])
  
  case class FileNames(fileNames: Seq[FileName])
  
  //----------------------------------------------
  // JSON
  //----------------------------------------------

  //  implicit object DateJsonFormat extends RootJsonFormat[Date] {
  //
  //    override def write(obj: Date) = JsString(obj.getTime.toString)
  //
  //    override def read(json: JsValue): Date = json match {
  //      case JsString(s) => new Date(java.lang.Long.parseLong(s))
  //      case _ => throw new DeserializationException("Error parsing DateTime string")
  //    }
  //  }

  object MetaData extends DefaultJsonProtocol {
    implicit val format = DefaultJsonProtocol.jsonFormat8(MetaData.apply)
  }

  object Patient extends DefaultJsonProtocol {
    implicit val format = DefaultJsonProtocol.jsonFormat2(Patient.apply)
  }

}