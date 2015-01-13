package se.vgregion.box

import se.vgregion.app.DbProps
import akka.actor.Actor
import akka.event.LoggingReceive
import se.vgregion.box.BoxProtocol._
import akka.actor.Props
import akka.actor.PoisonPill
import java.util.UUID

class BoxServiceActor(dbProps: DbProps) extends Actor {

  val db = dbProps.db
  val dao = new BoxDAO(dbProps.driver)

  setupDb()

  def receive = LoggingReceive {
    case msg: BoxRequest => msg match {

      case CreateConfig(name) =>
        val token = UUID.randomUUID().toString()
        addToken(token)
        // skapa fullständing url
        sender ! ConfigCreated(name, token)
        
      case AddBox(config) =>

        context.child(config.name) match {
          case Some(actor) =>
            sender ! BoxAdded(config)
          case None =>
            addConfig(config)
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

  def addConfig(config: BoxConfig) =
    db.withSession { implicit session =>
      dao.insertConfig(config)
    }

  def removeConfig(config: BoxConfig) =
    db.withSession { implicit session =>
      dao.removeConfig(config)
    }

  def getConfigs() =
    db.withSession { implicit session =>
      dao.listConfigs
    }

  def addToken(token: String) =
    db.withSession { implicit session =>
      dao.insertToken(token)
    }
}

object BoxServiceActor {
  def props(dbProps: DbProps): Props = Props(new BoxServiceActor(dbProps))
}