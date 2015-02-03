package se.vgregion.box

import scala.slick.driver.JdbcProfile
import org.h2.jdbc.JdbcSQLException
import scala.slick.jdbc.meta.MTable
import BoxProtocol._

class BoxDAO(val driver: JdbcProfile) {
  import driver.simple._

  val toServer = (id: Long, name: String, token: String, url: String) => BoxServerConfig(id, name, token, url)
  val fromServer = (server: BoxServerConfig) => Option((server.id, server.name, server.token, server.url))

  class ServerTable(tag: Tag) extends Table[BoxServerConfig](tag, "BoxServer") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def token = column[String]("token")
    def url = column[String]("url")
    def * = (id, name, token, url) <> (toServer.tupled, fromServer)
  }

  val serverQuery = TableQuery[ServerTable]

  val toClient = (id: Long, name: String, url: String) => BoxClientConfig(id, name, url)
  val fromClient = (config: BoxClientConfig) => Option((config.id, config.name, config.url))

  class ClientTable(tag: Tag) extends Table[BoxClientConfig](tag, "BoxClient") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def url = column[String]("url")
    def * = (id, name, url) <> (toClient.tupled, fromClient)
  }

  val clientQuery = TableQuery[ClientTable]

  def create(implicit session: Session) =
    if (MTable.getTables("BoxServer").list.isEmpty) {
      (clientQuery.ddl ++ serverQuery.ddl).create
    }

  def insertServer(server: BoxServerConfig)(implicit session: Session) = {
    val generatedId = (serverQuery returning serverQuery.map(_.id)) += server
    server.copy(id = generatedId)
  }

  def insertClient(client: BoxClientConfig)(implicit session: Session) = {
    val generatedId = (clientQuery returning clientQuery.map(_.id)) += client
    client.copy(id = generatedId)
  }

  def serverById(serverId: Long)(implicit session: Session): Option[BoxServerConfig] = 
    serverQuery.filter(_.id === serverId).list.headOption
    
  def clientById(clientId: Long)(implicit session: Session): Option[BoxClientConfig] = 
    clientQuery.filter(_.id === clientId).list.headOption
    
  def removeServer(serverId: Long)(implicit session: Session) =
    serverQuery.filter(_.id === serverId).delete

  def removeClient(clientId: Long)(implicit session: Session) =
    clientQuery.filter(_.id === clientId).delete

  def listServers(implicit session: Session): List[BoxServerConfig] =
    serverQuery.list

  def listClients(implicit session: Session): List[BoxClientConfig] =
    clientQuery.list

}