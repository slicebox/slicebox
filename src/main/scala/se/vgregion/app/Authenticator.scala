package se.vgregion.app

import scala.concurrent.ExecutionContext
import spray.routing.authentication.BasicAuth
import spray.routing.authentication.UserPass
import spray.routing.directives.AuthMagnet
import spray.routing.directives.AuthMagnet.fromContextAuthenticator
import se.vgregion.app.UserProtocol._
import akka.actor.ActorRef
import akka.util.Timeout
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import akka.pattern.ask
import scala.concurrent.Future
import spray.routing.Directives._

class Authenticator(userService: ActorRef) {

  implicit val timeout = Timeout(10 seconds)
    
  def basicUserAuthenticator(implicit ec: ExecutionContext): AuthMagnet[AuthInfo] = {

    def validateUser(optionalUserPass: Option[UserPass]): Future[Option[AuthInfo]] = {
      if (optionalUserPass.isDefined) {
        val userPass = optionalUserPass.get
        userService.ask(GetUserByName(userPass.user)).mapTo[Option[ApiUser]].map {
          case Some(repoUser) if (repoUser.passwordMatches(userPass.pass)) => Some(new AuthInfo(repoUser))
          case _ => None
        }
      } else
        Future.successful(None)
    }

    def authenticator(userPass: Option[UserPass]): Future[Option[AuthInfo]] = validateUser(userPass)

    BasicAuth(authenticator _, realm = "Slicebox")
  }
}
