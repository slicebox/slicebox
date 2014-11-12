package se.vgregion.dicom

import java.nio.file.Path
import java.io.File
import spray.json.DefaultJsonProtocol
import java.util.concurrent.Executor

object ScpProtocol {

  case class ScpData(name: String, aeTitle: String, port: Int)

  // incoming

  case class AddScp(scpData: ScpData)

  case object GetScpDataCollection

  case class AddScpWithExecutor(scpData: ScpData, executor: Executor)

  // outgoing

  case class ScpAdded(scpData: ScpData)

  case class ScpDataCollection(scpDataCollection: List[ScpData])

  //----------------------------------------------
  // JSON
  //----------------------------------------------

  object ScpData extends DefaultJsonProtocol {
    implicit val format = jsonFormat3(ScpData.apply)
  }

}