package se.vgregion.app

import UserProtocol._
import UserProtocol.UserRole._

case class AuthInfo(val user: ApiUser) {
  def hasPermission(role: UserRole): Boolean = (user.role, role) match {
    case (ADMINISTRATOR, _) => true
    case (USER, USER) => true
    case _ => false
  }
}