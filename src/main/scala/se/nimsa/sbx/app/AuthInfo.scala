package se.nimsa.sbx.app

import UserProtocol._
import UserProtocol.UserRole._

case class AuthInfo(val user: ApiUser) {
  def hasPermission(role: UserRole): Boolean = (user.role, role) match {
    case (SUPERUSER, _) => true
    case (ADMINISTRATOR, ADMINISTRATOR) => true
    case (ADMINISTRATOR, USER) => true
    case (USER, USER) => true
    case _ => false
  }
}