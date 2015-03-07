package se.vgregion.app

import org.mindrot.jbcrypt.BCrypt
import com.github.t3hnar.bcrypt.Password
import com.github.t3hnar.bcrypt.generateSalt
import se.vgregion.model.Entity

object UserProtocol {
  
  sealed trait UserRole {
    override def toString(): String = this match {
      case UserRole.SUPERUSER => "SUPERUSER"
      case UserRole.ADMINISTRATOR => "ADMINISTRATOR"
      case UserRole.USER => "USER"
    }
  }

  object UserRole {
    case object SUPERUSER extends UserRole
    case object ADMINISTRATOR extends UserRole
    case object USER extends UserRole

    def withName(string: String) = string match {
      case "SUPERUSER" => SUPERUSER
      case "ADMINISTRATOR" => ADMINISTRATOR
      case "USER" => USER
    }    
  }
  
  case class ClearTextUser(user: String, role: UserRole, password: String)

  case class ApiUser(id: Long, user: String, role: UserRole, hashedPassword: Option[String] = None) extends Entity {
  
    def withPassword(password: String) = copy (hashedPassword = Some(password.bcrypt(generateSalt)))
  
    def passwordMatches(password: String): Boolean = hashedPassword.exists(hp => BCrypt.checkpw(password, hp))
  
  }
  
  case class AuthToken(token: String)
  
  case class LoginResult(success: Boolean, message: String)
  
  sealed trait UserRequest
  
  case class AddUser(user: ApiUser) extends UserRequest
  case class UserAdded(user: ApiUser)
  
  case object GetUsers extends UserRequest
  case class Users(users: Seq[ApiUser])
  
  case class GetUser(userId: Long) extends UserRequest
  case class GetUserByName(user: String) extends UserRequest
  case class GetUserByAuthToken(authToken: AuthToken) extends UserRequest
  case class GenerateAuthTokens(user: ApiUser, numberOfTokens: Int) extends UserRequest
    
  case class DeleteUser(userId: Long) extends UserRequest
  case class UserDeleted(userId: Long)
}
