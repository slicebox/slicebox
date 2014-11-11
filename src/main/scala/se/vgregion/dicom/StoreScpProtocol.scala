package se.vgregion.dicom

import java.nio.file.Path
import java.io.File
import spray.json.DefaultJsonProtocol
import java.util.concurrent.Executor

object StoreScpProtocol {

  case class StoreScpData(name: String, aeTitle: String, port: Int)

  // incoming

  case class AddStoreScp(storeScpData: StoreScpData)

  case object GetStoreScpDataCollection

  case class AddStoreScpWithExecutor(storeScpData: StoreScpData, executor: Executor)

  // outgoing

  case class StoreScpAdded(storeScpData: StoreScpData)

  case class StoreScpDataCollection(storeScpDataCollection: List[StoreScpData])

  //----------------------------------------------
  // JSON
  //----------------------------------------------

  object StoreScpData extends DefaultJsonProtocol {
    implicit val format = jsonFormat3(StoreScpData.apply)
  }

}