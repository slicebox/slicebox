package se.nimsa.sbx.scp

import se.nimsa.sbx.model.Entity
import org.dcm4che3.data.Attributes

object ScpProtocol {
  
  case class ScpData(id: Long, name: String, aeTitle: String, port: Int) extends Entity

  
  sealed trait ScpRequest
  
  case class AddScp(name: String, aeTitle: String, port: Int) extends ScpRequest

  case class RemoveScp(id: Long) extends ScpRequest 

  case object GetScps extends ScpRequest 

  case class GetScpById(scpId: Long) extends ScpRequest
  
  case class Scps(scps: Seq[ScpData]) 


  case class ScpRemoved(scpDataId: Long)

  case class DatasetReceivedByScp(dataset: Attributes)

}