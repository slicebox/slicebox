package se.vgregion.app

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import spray.routing.authentication.BasicAuth
import spray.routing.authentication.UserPass
import spray.routing.directives.AuthMagnet
import spray.routing.directives.AuthMagnet.fromContextAuthenticator

class Authenticator(userRepository: UserRepository) {

  def basicUserAuthenticator(implicit ec: ExecutionContext): AuthMagnet[AuthInfo] = {

    def validateUser(optionalUserPass: Option[UserPass]): Future[Option[AuthInfo]] = {
      if (optionalUserPass.isDefined) {
        val userPass = optionalUserPass.get
        userRepository.userByName(userPass.user).map {
          case Some(repoUser) if (repoUser.passwordMatches(userPass.pass)) => Some(new AuthInfo(repoUser))
          case _ => None
        }
      } else
        Future.successful(None)
    }

    def authenticator(userPass: Option[UserPass]): Future[Option[AuthInfo]] = validateUser(userPass)

    BasicAuth(authenticator _, realm = "Slicebox API")
  }
}
