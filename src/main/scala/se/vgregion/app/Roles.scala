package se.vgregion.app

import org.json4s.CustomSerializer
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString

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

  class RoleSerializer extends CustomSerializer[Role](format => (
    {
      case JString(s) => valueOf(s)
      case _          => Collaborator
    },
    {
      case role: Role =>
        JString(role.toString)
    }))

}