package se.vgregion.dicom.scp

import java.nio.file.Path
import java.io.File
import spray.json.DefaultJsonProtocol
import java.util.concurrent.Executor

object ScpProtocol {

  case class ScpData(name: String, aeTitle: String, port: Int)

  // incoming

  case class AddScp(scpData: ScpData)

  case class DeleteScp(name: String)

  case object ShutdownScp
  
  case object GetScpDataCollection

  // outgoing

  case object ScpShutdown
  
  case object ScpSetupFailed
  
  case class ScpAdded(scpData: ScpData)

  case class ScpAlreadyAdded(scpData: ScpData)
  
  case class ScpDeleted(name: String)
  
  case class ScpNotFound(name: String)
  
  case class ScpDataCollection(scpDataCollection: Seq[ScpData])

  //----------------------------------------------
  // JSON
  //----------------------------------------------

  object ScpData extends DefaultJsonProtocol {
    implicit val format = jsonFormat3(ScpData.apply)
  }

  object ScpDataCollection extends DefaultJsonProtocol {
    implicit val format = jsonFormat1(ScpDataCollection.apply)
  }

  object DeleteScp extends DefaultJsonProtocol {
    implicit val format = jsonFormat1(DeleteScp.apply)
  }


}