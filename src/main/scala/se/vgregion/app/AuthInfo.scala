package se.vgregion.app

class AuthInfo(val user: ApiUser) {
  def hasPermission(role: Role): Boolean = (user.role, role) match {
    case (Administrator, _) => true
    case (User, User) => true
    case (User, Collaborator) => true
    case (Collaborator, Collaborator) => true
    case _ => false
  }
}