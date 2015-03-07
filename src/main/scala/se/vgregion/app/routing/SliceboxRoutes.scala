package se.vgregion.app.routing

import spray.routing.Route
import spray.http.StatusCodes.BadRequest
import se.vgregion.app.RestApi
import se.vgregion.app.UserProtocol.UserRole
import spray.routing.ExceptionHandler

trait SliceboxRoutes extends DirectoryRoutes
  with ScpRoutes
  with MetadataRoutes
  with ImageRoutes
  with SeriesRoutes
  with BoxRoutes
  with UserRoutes
  with LogRoutes
  with SystemRoutes
  with UiRoutes { this: RestApi =>

  implicit def knownExceptionHandler =
    ExceptionHandler {
      case e: IllegalArgumentException =>
        complete((BadRequest,  e.getMessage()))
    }

  def routes: Route =
    pathPrefix("api") {
      parameter('authtoken.?) { authToken =>
        authenticate(authenticator.basicUserAuthenticator(authToken)) { authInfo =>
          authorize(authInfo.hasPermission(UserRole.USER)) {
            directoryRoutes ~
              scpRoutes(authInfo) ~
              metaDataRoutes ~
              imageRoutes ~
              seriesRoutes ~
              boxRoutes(authInfo) ~
              userRoutes(authInfo) ~
              inboxRoutes ~
              outboxRoutes ~
              logRoutes ~
              systemRoutes(authInfo)
          }
        }
      }
    } ~ pathPrefixTest(!"api") {
      pathPrefix("assets") {
        staticResourcesRoute
      } ~ pathPrefixTest(!"assets") {
        loginRoute ~ angularRoute
      }
    }

}