package se.vgregion.box

import se.vgregion.model.Entity

object BoxProtocol {

  case class BoxServerName(name: String)
  
  case class BoxServerConfig(id: Long, name: String, token: String, url: String) extends Entity
  
  case class BoxClientConfig(id: Long, name: String, url: String) extends Entity
    
  sealed trait BoxRequest
  
  case class CreateBoxServer(name: String) extends BoxRequest
  
  case class AddBoxClient(client: BoxClientConfig) extends BoxRequest
  
  case class RemoveBoxClient(clientId: Long) extends BoxRequest
  
  case class RemoveBoxServer(serverId: Long) extends BoxRequest
  
  case object GetBoxClients extends BoxRequest
  
  case object GetBoxServers extends BoxRequest
  
  case class ValidateToken(token: String) extends BoxRequest

  case class BoxServerAdded(server: BoxServerConfig)
  
  case class BoxClientAdded(client: BoxClientConfig)

  case class BoxClientRemoved(clientId: Long)
  
  case class BoxServerRemoved(serverId: Long)
  
  case class BoxServers(servers: Seq[BoxServerConfig])
  
  case class BoxClients(clients: Seq[BoxClientConfig])
  
  case class BoxServerCreated(server: BoxServerConfig)
  
  case class ValidToken(token: String)

  case class InvalidToken(token: String)
  
}