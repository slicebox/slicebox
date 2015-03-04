package se.vgregion.app

import scala.language.postfixOps
import UserRepositoryDbProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.util.ExceptionCatching

class UserRepositoryDbActor(dbProps: DbProps) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new UserDAO(dbProps.driver)

  setupDb()

  def receive = LoggingReceive {

    case msg: UserRequest =>
      catchAndReport {
        
        msg match {
        
          case AddUser(apiUser) =>
            db.withSession { implicit session =>
              sender ! UserAdded(dao.insert(apiUser))
            }
            
          case GetUser(userId) =>
            db.withSession { implicit session =>
              dao.userById(userId)
            }
            
          case GetUserByName(user) =>
            db.withSession { implicit session =>
              dao.userByName(user)
            }
            
          case GetUsers=>
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
  
}

object UserRepositoryDbActor {
  def props(dbProps: DbProps): Props = Props(new UserRepositoryDbActor(dbProps))
}
