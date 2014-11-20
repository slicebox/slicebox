package se.vgregion.dicom

import java.nio.file.Path
import spray.json.DefaultJsonProtocol

object MetaDataProtocol {

  case class MetaData(
    patientName: String,
    patientId: String,
    studyInstanceUID: String,
    studyDate: String,
    seriesInstanceUID: String,
    seriesDate: String,
    sopInstanceUID: String,
    fileName: String)

  // incoming

  case class AddMetaData(path: Path)

  case class DeleteMetaData(path: Path)

  case object GetMetaDataCollection

  // outgoing

  case class MetaDataCollection(metaDataCollection: Seq[MetaData])

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

}