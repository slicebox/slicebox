package se.vgregion.box

import se.vgregion.app.DbProps
import akka.actor.Actor
import akka.event.LoggingReceive
import se.vgregion.box.BoxProtocol._
import akka.actor.Props
import akka.actor.PoisonPill
import java.util.UUID

class BoxServiceActor(dbProps: DbProps, host: String, port: Int) extends Actor {

  val db = dbProps.db
  val dao = new BoxDAO(dbProps.driver)

  setupDb()

  def receive = LoggingReceive {
    case msg: BoxRequest => msg match {

      case CreateBox(name) =>
        
        val token = UUID.randomUUID().toString()
        val url = s"http://$host/$port/api/box/$token"
        val config = BoxConfig(name, url)
        addConfig(config, false)
        sender ! BoxCreated(config)
        
      case AddBox(config) =>

        context.child(config.name) match {
          case Some(actor) =>
            sender ! BoxAdded(config)
          case None =>
            addConfig(config, true)
            context.actorOf(BoxActor.props(config), config.name)
            sender ! BoxAdded(config)
        }

      case RemoveBox(config) =>

        context.child(config.name) match {
          case Some(actor) =>
            removeConfig(config)
            actor ! PoisonPill
            sender ! BoxRemoved(config)
          case None =>
            sender ! BoxRemoved(config)
        }

      case GetBoxes =>
        
        val boxes = getConfigs()
        sender ! Boxes(boxes)

    }
  }

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def addConfig(config: BoxConfig, active: Boolean) =
    db.withSession { implicit session =>
      dao.insertConfig(config, active)
    }

  def removeConfig(config: BoxConfig) =
    db.withSession { implicit session =>
      dao.removeConfig(config)
    }

  def getConfigs() =
    db.withSession { implicit session =>
      dao.listConfigs
    }

}

object BoxServiceActor {
  def props(dbProps: DbProps, host: String, port: Int): Props = Props(new BoxServiceActor(dbProps, host, port))
}