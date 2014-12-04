package se.vgregion.app

import scala.concurrent.Future

trait UserRepository {
  
  def userByName(name: String): Future[Option[ApiUser]]
  
  def addUser(user: ApiUser): Future[Option[ApiUser]]
  
}