package se.vgregion.app.routing

import spray.routing._
import spray.http.StatusCodes._
import se.vgregion.app.RestApi
import se.vgregion.app.UserProtocol.UserRole
import spray.routing.ExceptionHandler

trait SliceboxRoutes extends DirectoryRoutes
  with ScpRoutes
  with MetadataRoutes
  with ImageRoutes
  with SeriesRoutes
  with BoxRoutes
  with RemoteBoxRoutes
  with UserRoutes
  with LogRoutes
  with SystemRoutes
  with UiRoutes { this: RestApi =>

  implicit val knownExceptionHandler =
    ExceptionHandler {
      case e: IllegalArgumentException =>
        complete((BadRequest, e.getMessage()))
    }

  def sliceboxRoutes: Route =
    pathPrefix("api") {
      parameter('authtoken.?) { authToken =>
        authenticate(authenticator.basicUserAuthenticator(authToken)) { authInfo =>
          directoryRoutes(authInfo) ~
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
      } ~ remoteBoxRoutes
    } ~ pathPrefixTest(!"api") {
      pathPrefix("assets") {
        staticResourcesRoute
      } ~ pathPrefixTest(!"assets") {
        loginRoute ~ angularRoute
      }
    }

}