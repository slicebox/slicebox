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
      getFromResource("public/images/favicons/apple-touch-icon-57x57.png")
    } ~ path("apple-touch-icon-60x60.png") {
      getFromResource("public/images/favicons/apple-touch-icon-60x60.png")
    } ~ path("apple-touch-icon-72x72.png") {
      getFromResource("public/images/favicons/apple-touch-icon-72x72.png")
    } ~ path("apple-touch-icon-76x76.png") {
      getFromResource("public/images/favicons/apple-touch-icon-76x76.png")
    } ~ path("apple-touch-icon-114x114.png") {
      getFromResource("public/images/favicons/apple-touch-icon-114x114.png")
    } ~ path("apple-touch-icon-120x120.png") {
      getFromResource("public/images/favicons/apple-touch-icon-120x120.png")
    } ~ path("apple-touch-icon-144x144.png") {
      getFromResource("public/images/favicons/apple-touch-icon-144x144.png")
    } ~ path("apple-touch-icon-152x152.png") {
      getFromResource("public/images/favicons/apple-touch-icon-152x152.png")
    } ~ path("apple-touch-icon-180x180.png") {
      getFromResource("public/images/favicons/apple-touch-icon-180x180.png")
    } ~ path("favicon.ico") {
      getFromResource("public/images/favicons/favicon.ico")
    } ~ path("favicon-16x16.png") {
      getFromResource("public/images/favicons/favicon-16x16.png")
    } ~ path("favicon-32x32.png") {
      getFromResource("public/images/favicons/favicon-32x32.png")
    } ~ path("favicon-96x96.png") {
      getFromResource("public/images/favicons/favicon-96x96.png")
    } ~ path("favicon-194x194.png") {
      getFromResource("public/images/favicons/favicon-194x194.png")
    } ~ path("android-chrome-36x36.png") {
      getFromResource("public/images/favicons/android-chrome-36x36.png")
    } ~ path("android-chrome-48x48.png") {
      getFromResource("public/images/favicons/android-chrome-48x48.png")
    } ~ path("android-chrome-72x72.png") {
      getFromResource("public/images/favicons/android-chrome-72x72.png")
    } ~ path("android-chrome-96x96.png") {
      getFromResource("public/images/favicons/android-chrome-96x96.png")
    } ~ path("android-chrome-144x144.png") {
      getFromResource("public/images/favicons/android-chrome-144x144.png")
    } ~ path("android-chrome-192x192.png") {
      getFromResource("public/images/favicons/android-chrome-192x192.png")
    } ~ path("mstile-70x70.png") {
      getFromResource("public/images/favicons/mstile-70x70.png")
    } ~ path("mstile-144x144.png") {
      getFromResource("public/images/favicons/mstile-144x144.png")
    } ~ path("mstile-150x150.png") {
      getFromResource("public/images/favicons/mstile-150x150.png")
    } ~ path("mstile-310x150.png") {
      getFromResource("public/images/favicons/mstile-310x150.png")
    } ~ path("mstile-310x310.png") {
      getFromResource("public/images/favicons/mstile-310x310.png")
    } ~ path("manifest.json") {
      getFromResource("public/images/favicons/manifest.json")
    } ~ path("browserconfig.xml") {
      getFromResource("public/images/favicons/browserconfig.xml")
    }

}