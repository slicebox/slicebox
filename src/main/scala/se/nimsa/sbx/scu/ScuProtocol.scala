package se.nimsa.sbx.scu

import se.nimsa.sbx.model.Entity

object ScuProtocol {
  
  case class ScuData(id: Long, name: String, aeTitle: String, host: String, port: Int) extends Entity


  sealed trait ScuRequest
  
  case class AddScu(name: String, aeTitle: String, host: String, port: Int) extends ScuRequest

  case class RemoveScu(id: Long) extends ScuRequest 

  case object GetScus extends ScuRequest 

  case class SendSeriesToScp(seriesId: Long, scuId: Long) extends ScuRequest
  
  case class Scus(scps: Seq[ScuData]) 


  case class ScuRemoved(scuDataId: Long)

  case class ImagesSentToScp(scuId: Long, imageIds: Seq[Long])
  
}