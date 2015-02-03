package se.vgregion.box

import se.vgregion.app.DbProps
import akka.actor.Actor
import akka.event.LoggingReceive
import se.vgregion.box.BoxProtocol._
import akka.actor.Props
import akka.actor.PoisonPill
import java.util.UUID
import scala.util.Failure

class BoxServiceActor(dbProps: DbProps, host: String, port: Int) extends Actor {

  val db = dbProps.db
  val dao = new BoxDAO(dbProps.driver)

  setupDb()

  def receive = LoggingReceive {
    case msg: BoxRequest => msg match {

      case CreateBoxServer(name) =>

        val token = UUID.randomUUID().toString()
        val url = s"http://$host/$port/api/box/$token"
        val server = BoxServerConfig(-1, name, token, url)
        addServer(server)
        sender ! BoxServerCreated(server)

      case AddBoxClient(client) =>
        // TODO sanitize name when used as actor name
        context.child(client.name) match {
          case Some(actor) =>
            sender ! BoxClientAdded(client)
          case None =>
            addClient(client)
            context.actorOf(BoxClientActor.props(client), client.name)
            sender ! BoxClientAdded(client)
        }

      case RemoveBoxServer(serverId) =>
        removeServer(serverId)
        sender ! BoxServerRemoved(serverId)

      case RemoveBoxClient(clientId) =>

        clientById(clientId).foreach(client => context.child(client.name).foreach(_ ! PoisonPill))
        removeClient(clientId)
        sender ! BoxClientRemoved(clientId)

      case GetBoxClients =>
        val clients = getClients()
        sender ! BoxClients(clients)

      case GetBoxServers =>
        val servers = getServers()
        sender ! BoxServers(servers)

      case ValidateToken(token) =>
        if (tokenIsValid(token))
          sender ! ValidToken(token)
        else
          sender ! InvalidToken(token)
    }
  }

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def addServer(config: BoxServerConfig): BoxServerConfig =
    db.withSession { implicit session =>
      dao.insertServer(config)
    }

  def addClient(config: BoxClientConfig): BoxClientConfig =
    db.withSession { implicit session =>
      dao.insertClient(config)
    }

  def serverById(boxServerId: Long): Option[BoxServerConfig] =
    db.withSession { implicit session =>
      dao.serverById(boxServerId)
    }

  def clientById(boxClientId: Long): Option[BoxClientConfig] =
    db.withSession { implicit session =>
      dao.clientById(boxClientId)
    }

  def removeServer(boxServerId: Long) =
    db.withSession { implicit session =>
      dao.removeServer(boxServerId)
    }

  def removeClient(boxClientId: Long) =
    db.withSession { implicit session =>
      dao.removeClient(boxClientId)
    }

  def getClients(): Seq[BoxClientConfig] =
    db.withSession { implicit session =>
      dao.listClients
    }

  def getServers(): Seq[BoxServerConfig] =
    db.withSession { implicit session =>
      dao.listServers
    }

  def tokenIsValid(token: String): Boolean =
    db.withSession { implicit session =>
      true // TODO
    }
}

object BoxServiceActor {
  def props(dbProps: DbProps, host: String, port: Int): Props = Props(new BoxServiceActor(dbProps, host, port))
}