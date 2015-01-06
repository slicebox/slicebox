package se.vgregion.app

sealed trait Role

case object Administrator extends Role
case object User extends Role
case object Collaborator extends Role

object Role {

  def valueOf(string: String) = string match {
    case "Administrator" => Administrator
    case "User"          => User
    case _               => Collaborator
  }

}