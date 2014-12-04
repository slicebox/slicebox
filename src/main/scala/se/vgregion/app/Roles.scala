package se.vgregion.app

import spray.json.DefaultJsonProtocol
import spray.json.JsValue
import spray.json.JsonFormat
import spray.json.JsString

sealed trait Role

case object Administrator extends Role
case object User extends Role
case object Collaborator extends Role

object Role extends DefaultJsonProtocol {

  def valueOf(string: String) = string match {
    case "Administrator" => Administrator
    case "User" => User
    case _ => Collaborator
  }
  
  implicit object RoleFormat extends JsonFormat[Role] {
    def write(obj: Role) = JsString(obj.toString)

    def read(json: JsValue): Role = json match {
      case JsString(string) => valueOf(string)
      case _ => Collaborator
    }
  }
  
}