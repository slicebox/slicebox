package se.vgregion.box

object BoxProtocol {

  case class BoxConfig(name: String, url: String)
    
  case class BoxName(name: String)
  
  sealed trait BoxRequest
  
  case class AddBox(config: BoxConfig) extends BoxRequest
  
  case class RemoveBox(config: BoxConfig) extends BoxRequest
  
  case object GetBoxes extends BoxRequest
  
  case class CreateBox(name: String) extends BoxRequest
  
  case class BoxAdded(config: BoxConfig)
  
  case class BoxRemoved(config: BoxConfig)
  
  case class Boxes(configs: Seq[BoxConfig])
  
  case class BoxCreated(config: BoxConfig)
  
}