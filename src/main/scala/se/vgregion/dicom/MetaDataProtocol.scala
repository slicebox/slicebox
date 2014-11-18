package se.vgregion.dicom

import java.nio.file.Path
import java.io.File
import java.util.concurrent.Executor
import java.util.Date
import spray.httpx.marshalling.Marshaller
import spray.http.DateTime
import spray.json.RootJsonFormat
import spray.json.JsString
import spray.json.JsValue
import spray.json.DeserializationException
import spray.json.DefaultJsonProtocol

object MetaDataProtocol {

  case class MetaData(
      patientName: String, 
      patientId: String, 
      studyInstanceUID: String, 
      studyDate: Date, 
      seriesInstanceUID: String, 
      seriesDate: Date, 
      sopInstanceUID: String, 
      fileName: String)

  // incoming

  case class AddMetaData(metaData: MetaData)

  case class DeleteMetaData(metaData: MetaData)

  case object GetMetaDataCollection

  // outgoing

  case class MetaDataCollection(metaDataCollection: Seq[MetaData])

  //----------------------------------------------
  // JSON
  //----------------------------------------------

  implicit object DateJsonFormat extends RootJsonFormat[Date] {

    override def write(obj: Date) = JsString(obj.getTime.toString)

    override def read(json: JsValue): Date = json match {
      case JsString(s) => new Date(java.lang.Long.parseLong(s))
      case _ => throw new DeserializationException("Error parsing DateTime string")
    }
  }

  object MetaData extends DefaultJsonProtocol {
    implicit val format = DefaultJsonProtocol.jsonFormat8(MetaData.apply)
  }

}