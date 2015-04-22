package se.nimsa.sbx.app.routing

import akka.pattern.ask

import spray.http.StatusCodes.NoContent
import spray.http.StatusCodes.Created
import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.nimsa.sbx.app.AuthInfo
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.app.UserProtocol.UserRole
import se.nimsa.sbx.dicom.DicomProtocol._

trait ScuRoutes { this: RestApi =>

  def scuRoutes(authInfo: AuthInfo): Route =
    pathPrefix("scus") {
      pathEndOrSingleSlash {
        get {
          onSuccess(dicomService.ask(GetScus)) {
            case Scus(scus) =>
              complete(scus)
          }
        } ~ post {
          authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
            entity(as[AddScu]) { addScu =>
              onSuccess(dicomService.ask(addScu)) {
                case scuData: ScuData =>
                  complete((Created, scuData))
              }
            }
          }
        }
      } ~ pathPrefix(LongNumber) { scuDataId =>
        pathEndOrSingleSlash {
          delete {
            authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
              onSuccess(dicomService.ask(RemoveScu(scuDataId))) {
                case ScuRemoved(scuDataId) =>
                  complete(NoContent)
              }
            }
          }
        } ~ path("sendseries" / LongNumber) { seriesId =>
          post {
            onSuccess(dicomService.ask(SendSeriesToScp(seriesId, scuDataId))) {
              case ImagesSentToScp(scuDataId, imageIds) => complete(NoContent)
            }
          }
        }
      }
    }

}