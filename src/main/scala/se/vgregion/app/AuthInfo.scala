package se.vgregion.app

import UserRepositoryDbProtocol._
import UserRepositoryDbProtocol.UserRole._

class AuthInfo(val user: ApiUser) {
  def hasPermission(role: UserRole): Boolean = (user.role, role) match {
    case (ADMINISTRATOR, _) => true
    case (USER, USER) => true
    case _ => false
  }
}