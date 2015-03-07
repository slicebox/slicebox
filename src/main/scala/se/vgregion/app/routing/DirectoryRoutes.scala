package se.vgregion.app.routing

import akka.pattern.ask

import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.vgregion.app.RestApi
import se.vgregion.dicom.DicomProtocol._

trait DirectoryRoutes { this: RestApi =>

  def directoryRoutes: Route =
    pathPrefix("directorywatches") {
      pathEndOrSingleSlash {
        get {
          onSuccess(dicomService.ask(GetWatchedDirectories)) {
            case WatchedDirectories(directories) =>
              complete(directories)
          }
        } ~ post {
          entity(as[WatchDirectory]) { directory =>
            onSuccess(dicomService.ask(directory)) {
              case DirectoryWatched(path) =>
                complete("Now watching directory " + path)
            }
          }
        }
      } ~ path(LongNumber) { watchDirectoryId =>
        delete {
          onSuccess(dicomService.ask(UnWatchDirectory(watchDirectoryId))) {
            case DirectoryUnwatched(watchedDirectoryId) =>
              complete("Stopped watching directory " + watchedDirectoryId)
          }
        }
      }
    }

}