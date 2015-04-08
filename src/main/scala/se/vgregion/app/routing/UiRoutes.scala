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
  
  def faviconRoutes: Route =
    path("apple-touch-icon-57x57.png") {
      getFromResource("public/images/apple-touch-icon-57x57.png")
    } ~
    path("apple-touch-icon-60x60.png") {
      getFromResource("public/images/apple-touch-icon-60x60.png")
    } ~
    path("apple-touch-icon-72x72.png") {
      getFromResource("public/images/apple-touch-icon-72x72.png")
    } ~
    path("apple-touch-icon-76x76.png") {
      getFromResource("public/images/apple-touch-icon-76x76.png")
    } ~
    path("apple-touch-icon-114x114.png") {
      getFromResource("public/images/apple-touch-icon-114x114.png")
    } ~
    path("apple-touch-icon-120x120.png") {
      getFromResource("public/images/apple-touch-icon-120x120.png")
    } ~
    path("apple-touch-icon-144x144.png") {
      getFromResource("public/images/apple-touch-icon-144x144.png")
    } ~
    path("apple-touch-icon-152x152.png") {
      getFromResource("public/images/apple-touch-icon-152x152.png")
    } ~
    path("apple-touch-icon-180x180.png") {
      getFromResource("public/images/apple-touch-icon-180x180.png")
    } ~
    path("favicon-32x32.png") {
      getFromResource("public/images/favicon-32x32.png")
    } ~
    path("favicon-194x194.png") {
      getFromResource("public/images/favicon-194x194.png")
    } ~
    path("favicon-96x96.png") {
      getFromResource("public/images/favicon-96x96.png")
    } ~
    path("android-chrome-192x192.png") {
      getFromResource("public/images/android-chrome-192x192.png")
    } ~
    path("favicon-16x16.png") {
      getFromResource("public/images/favicon-16x16.png")
    } ~
    path("mstile-144x144.png") {
      getFromResource("public/images/mstile-144x144.png")
    }

}