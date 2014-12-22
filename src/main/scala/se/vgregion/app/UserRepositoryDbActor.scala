package se.vgregion.app

import scala.language.postfixOps
import UserRepositoryDbProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.dicom.DicomDispatchProtocol.Initialized

class UserRepositoryDbActor(dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new UserDAO(dbProps.driver)

  def receive = LoggingReceive {
    case Initialize =>
      db.withSession { implicit session =>
        dao.create
      }
      sender ! Initialized

    case GetUserByName(name) =>
      db.withSession { implicit session =>
        sender ! dao.findUserByName(name)
      }
    case GetUserNames =>
      db.withSession { implicit session =>
        sender ! dao.listUserNames
      }
    case AddUser(apiUser) =>
      db.withSession { implicit session =>
        sender ! dao.insert(apiUser)
      }
    case DeleteUser(userName) =>
      db.withSession { implicit session =>
        sender ! dao.delete(userName)
      }
	}
  
}

object UserRepositoryDbActor {
	def props(dbProps: DbProps): Props = Props(new UserRepositoryDbActor(dbProps))
}
