package se.vgregion.app.routing

import akka.pattern.ask

import spray.httpx.SprayJsonSupport._
import spray.routing._
import spray.routing.authentication.UserPass

import se.vgregion.app.RestApi
import se.vgregion.app.UserProtocol._

trait UiRoutes { this: RestApi =>

  def staticResourcesRoute =
    get {
      path(Rest) { path =>
        getFromResource("public/" + path)
      }
    }

  def angularRoute =
    get {
      getFromResource("public/index.html")
    }

  def loginRoute: Route =
    path("login") {
      post {
        entity(as[UserPass]) { userPass =>
          onSuccess(userService.ask(GetUserByName(userPass.user)).mapTo[Option[ApiUser]]) {
            case Some(repoUser) if (repoUser.passwordMatches(userPass.pass)) =>
              complete(LoginResult(true, repoUser.role, s"User ${userPass.user} logged in"))
            case _ =>
              complete(LoginResult(false, UserRole.USER, "Incorrect username or password"))
          }
        }
      }
    }

}