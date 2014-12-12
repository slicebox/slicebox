package se.vgregion.db

import se.vgregion.app.UserRepository
import scala.concurrent.Future
import se.vgregion.app.ApiUser
import akka.pattern.ask
import akka.actor.ActorRef
import DbProtocol._
import akka.util.Timeout
import scala.concurrent.duration._

import scala.language.postfixOps

class DbUserRepository(dbActor: ActorRef) extends UserRepository {

  implicit val timeout = Timeout(10 seconds)
  
  def userByName(name: String): Future[Option[ApiUser]] = dbActor.ask(GetUserByName(name)).mapTo[Option[ApiUser]]
  
  def addUser(user: ApiUser): Future[Option[ApiUser]] = dbActor.ask(AddUser(user)).mapTo[Option[ApiUser]]    
  
  def deleteUser(userName: String): Future[Option[ApiUser]] = dbActor.ask(DeleteUser(userName)).mapTo[Option[ApiUser]]    
  
  def listUserNames(): Future[List[String]] = dbActor.ask(GetUserNames).mapTo[List[String]]
  
}