package se.vgregion.app

import org.mindrot.jbcrypt.BCrypt

import com.github.t3hnar.bcrypt.Password
import com.github.t3hnar.bcrypt.generateSalt

case class ClearTextUser(user: String, role: Role, password: String)

case class ApiUser(user: String, role: Role, hashedPassword: Option[String] = None) {

  def withPassword(password: String) = copy (hashedPassword = Some(password.bcrypt(generateSalt)))

  def passwordMatches(password: String): Boolean = hashedPassword.exists(hp => BCrypt.checkpw(password, hp))

}
