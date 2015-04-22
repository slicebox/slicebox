package se.nimsa.sbx.app.routing

import akka.pattern.ask
import spray.httpx.SprayJsonSupport._
import spray.routing._
import spray.http.StatusCodes._
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.dicom.DicomProtocol._
import se.nimsa.sbx.app.AuthInfo
import se.nimsa.sbx.app.UserProtocol.UserRole

trait DirectoryRoutes { this: RestApi =>

  def directoryRoutes(authInfo: AuthInfo): Route =
    pathPrefix("directorywatches") {
      pathEndOrSingleSlash {
        get {
          onSuccess(dicomService.ask(GetWatchedDirectories)) {
            case WatchedDirectories(directories) =>
              complete(directories)
          }
        } ~ post {
          authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
            entity(as[WatchDirectory]) { directory =>
              onSuccess(dicomService.ask(directory)) {
                case dir: WatchedDirectory =>
                  complete((Created, dir))
              }
            }
          }
        }
      } ~ path(LongNumber) { watchDirectoryId =>
        delete {
          authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
            onSuccess(dicomService.ask(UnWatchDirectory(watchDirectoryId))) {
              case DirectoryUnwatched(watchedDirectoryId) =>
                complete(NoContent)
            }
          }
        }
      }
    }

}