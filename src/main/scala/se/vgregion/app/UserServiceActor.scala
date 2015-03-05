package se.vgregion.app

import scala.language.postfixOps
import UserProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.util.ExceptionCatching

class UserServiceActor(dbProps: DbProps, superUser: String, superPassword: String) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new UserDAO(dbProps.driver)

  setupDb()
  addSuperUser()

  def receive = LoggingReceive {

    case msg: UserRequest =>
      catchAndReport {

        msg match {

          case AddUser(apiUser) =>
            db.withSession { implicit session =>
              sender ! UserAdded(dao.userByName(apiUser.user).getOrElse(dao.insert(apiUser)))
            }

          case GetUser(userId) =>
            db.withSession { implicit session =>
              sender ! dao.userById(userId)
            }

          case GetUserByName(user) =>
            db.withSession { implicit session =>
              sender ! dao.userByName(user)
            }

          case GetUsers =>
            db.withSession { implicit session =>
              sender ! Users(dao.listUsers)
            }

          case DeleteUser(userId) =>
            db.withSession { implicit session =>
              dao.removeUser(userId)
              sender ! UserDeleted(userId)
            }
        }
      }
  }

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def addSuperUser() =
    db.withSession { implicit session =>
      dao.userByName(superUser)
        .getOrElse(dao.insert(
          ApiUser(-1, superUser, UserRole.ADMINISTRATOR).withPassword(superPassword)))
    }

}

object UserServiceActor {
  def props(dbProps: DbProps, superUser: String, superPassword: String): Props = Props(new UserServiceActor(dbProps, superUser, superPassword))
}
