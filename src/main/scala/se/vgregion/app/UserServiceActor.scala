package se.vgregion.app

import scala.language.postfixOps
import UserProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.util.ExceptionCatching
import scala.collection.mutable.Map
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import java.util.UUID

class UserServiceActor(dbProps: DbProps, superUser: String, superPassword: String) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new UserDAO(dbProps.driver)

  val authTokens = Map.empty[AuthToken, Tuple2[ApiUser, Long]]

  setupDb()
  addSuperUser()

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val authTokenCleaner = system.scheduler.schedule(12.hours, 12.hours) {
    self ! CleanupTokens
  }

  override def postStop() =
    authTokenCleaner.cancel()
    
  def receive = LoggingReceive {

    case msg: UserRequest =>
      catchAndReport {

        msg match {

          case AddUser(apiUser) =>
            if (apiUser.role == UserRole.SUPERUSER)
              throw new IllegalArgumentException("Superusers may not be added")
            
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
              dao.userById(userId)
                .filter(_.role == UserRole.SUPERUSER)
                .foreach(superuser => throw new IllegalArgumentException("Superuser may not be deleted"))
       
              dao.removeUser(userId)
              sender ! UserDeleted(userId)
            }

          case GetUserByAuthToken(token) =>
            sender ! authTokens.remove(token).map(_._1)

          case GenerateAuthTokens(user, numberOfTokens) =>
            val generatedTokens = generateNewTokens(user, numberOfTokens)
            sender ! generatedTokens

        }
      }
      
    case CleanupTokens => 
      cleanupTokens()
  }

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def addSuperUser() =
    db.withSession { implicit session =>
      dao.userByName(superUser)
        .getOrElse(dao.insert(
          ApiUser(-1, superUser, UserRole.SUPERUSER).withPassword(superPassword)))
    }

  def generateNewTokens(user: ApiUser, numberOfTokens: Int): List[AuthToken] = {
    (1 to numberOfTokens).map(i => {
      val authToken = AuthToken(UUID.randomUUID.toString)
      authTokens += authToken -> ((user, System.currentTimeMillis))
      authToken
    }).toList
  }

  def cleanupTokens(): Unit = {
    val timeNow = System.currentTimeMillis
    val expiredKeys = authTokens.filter {
      case (key, value) => (timeNow - value._2) > 24.hours.toMillis
    }.map {
      case (key, value) => key
    }
    authTokens --= expiredKeys
  }
}

object UserServiceActor {
  def props(dbProps: DbProps, superUser: String, superPassword: String): Props = Props(new UserServiceActor(dbProps, superUser, superPassword))
}
