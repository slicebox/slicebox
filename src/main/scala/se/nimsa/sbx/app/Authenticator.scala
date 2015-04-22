package se.nimsa.sbx.app

import scala.concurrent.ExecutionContext
import spray.routing.authentication.BasicAuth
import spray.routing.authentication.UserPass
import spray.routing.directives.AuthMagnet
import spray.routing.directives.AuthMagnet.fromContextAuthenticator
import akka.actor.ActorRef
import akka.util.Timeout
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import akka.pattern.ask
import scala.concurrent.Future
import spray.routing.Directives._
import UserProtocol._

class Authenticator(userService: ActorRef) {

  implicit val timeout = Timeout(10 seconds)

  def basicUserAuthenticator(optionalAuthToken: Option[String])(implicit ec: ExecutionContext): AuthMagnet[AuthInfo] = {

    def validateUser(optionalUserPass: Option[UserPass]): Future[Option[AuthInfo]] = {
      val optionalFutureAuthInfo =
        if (optionalAuthToken.isDefined)
          optionalAuthToken.map(authToken =>
            userService.ask(GetUserByAuthToken(AuthToken(authToken))).mapTo[Option[ApiUser]].map(_.map(AuthInfo(_))))
        else
          optionalUserPass.map(userPass =>
            userService.ask(GetUserByName(userPass.user)).mapTo[Option[ApiUser]].map {
              case Some(repoUser) if (repoUser.passwordMatches(userPass.pass)) => Some(new AuthInfo(repoUser))
              case _ => None
            })
      optionalFutureAuthInfo.getOrElse(Future.successful(None))
    }

    def authenticator(userPass: Option[UserPass]): Future[Option[AuthInfo]] = validateUser(userPass)

    BasicAuth(authenticator _, realm = "Slicebox")
  }
}
