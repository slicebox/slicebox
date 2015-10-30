/*
 * Copyright 2015 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.user

import org.mindrot.jbcrypt.BCrypt
import com.github.t3hnar.bcrypt.Password
import com.github.t3hnar.bcrypt.generateSalt
import se.nimsa.sbx.model.Entity
import java.net.InetAddress
import spray.routing.authentication.UserPass

object UserProtocol {

  sealed trait UserRole {
    override def toString(): String = this match {
      case UserRole.SUPERUSER     => "SUPERUSER"
      case UserRole.ADMINISTRATOR => "ADMINISTRATOR"
      case UserRole.USER          => "USER"
    }
  }

  object UserRole {
    case object SUPERUSER extends UserRole
    case object ADMINISTRATOR extends UserRole
    case object USER extends UserRole

    def withName(string: String) = string match {
      case "SUPERUSER"     => SUPERUSER
      case "ADMINISTRATOR" => ADMINISTRATOR
      case "USER"          => USER
    }
  }

  case class ClearTextUser(user: String, role: UserRole, password: String)

  case class ApiUser(id: Long, user: String, role: UserRole, hashedPassword: Option[String] = None) extends Entity {

    def withPassword(password: String) = copy(hashedPassword = Some(password.bcrypt(generateSalt)))

    def passwordMatches(password: String): Boolean = hashedPassword.exists(hp => BCrypt.checkpw(password, hp))

  }

  case class ApiSession(id: Long, userId: Long, token: String, ip: String, userAgent: String, lastUpdated: Long) extends Entity

  case class AuthKey(token: Option[String], ip: Option[String], userAgent: Option[String])

  sealed trait UserRequest

  case class Login(userPass: UserPass, authKey: AuthKey) extends UserRequest
  case class Logout(user: ApiUser, authKey: AuthKey) extends UserRequest
  case class AddUser(user: ApiUser) extends UserRequest
  case object GetUsers extends UserRequest
  case class GetUserByName(user: String) extends UserRequest
  case class GetAndRefreshUserByAuthKey(token: AuthKey) extends UserRequest
  case class DeleteUser(userId: Long) extends UserRequest

  case object RemoveExpiredSessions

  case class LoginResult(success: Boolean, role: UserRole, message: String)
  case class LoggedIn(user: ApiUser, session: ApiSession)
  case object LoginFailed
  case object LoggedOut
  case class Users(users: Seq[ApiUser])
  case class UserAdded(user: ApiUser)
  case class UserDeleted(userId: Long)

}
