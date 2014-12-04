package se.vgregion.app

import com.github.t3hnar.bcrypt._
import org.mindrot.jbcrypt.BCrypt
import spray.json.DefaultJsonProtocol

case class AuthenticationUser(user: String, password: String)

case class FullUser(user: String, role: Role, password: String)

case class ApiUser(user: String, role: Role, hashedPassword: Option[String] = None) {

  def withPassword(password: String) = copy (hashedPassword = Some(password.bcrypt(generateSalt)))

  def passwordMatches(password: String): Boolean = hashedPassword.exists(hp => BCrypt.checkpw(password, hp))

}

object AuthenticationUser extends DefaultJsonProtocol {
  implicit val format = DefaultJsonProtocol.jsonFormat2(AuthenticationUser.apply)
}

object FullUser extends DefaultJsonProtocol {
  implicit val format = DefaultJsonProtocol.jsonFormat3(FullUser.apply)
}
