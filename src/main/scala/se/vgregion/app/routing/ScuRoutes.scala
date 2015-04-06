package se.vgregion.app.routing

import akka.pattern.ask

import spray.http.StatusCodes.NoContent
import spray.http.StatusCodes.Created
import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.vgregion.app.AuthInfo
import se.vgregion.app.RestApi
import se.vgregion.app.UserProtocol.UserRole
import se.vgregion.dicom.DicomProtocol._

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
      } ~ path(LongNumber) { scuDataId =>
        delete {
          authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
            onSuccess(dicomService.ask(RemoveScu(scuDataId))) {
              case ScuRemoved(scuDataId) =>
                complete(NoContent)
            }
          }
        }
      }
    }

}