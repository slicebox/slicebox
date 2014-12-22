package se.vgregion.app

import scala.concurrent.Future

trait UserRepository {
  
  def initialize(): Future[Any]
  
  def userByName(name: String): Future[Option[ApiUser]]
  
  def addUser(user: ApiUser): Future[Option[ApiUser]]
 
  def deleteUser(userName: String): Future[Option[ApiUser]]
 
  def listUserNames(): Future[List[String]]
}