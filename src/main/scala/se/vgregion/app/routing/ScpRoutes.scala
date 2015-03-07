package se.vgregion.app.routing

import akka.pattern.ask

import spray.http.StatusCodes.NoContent
import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.vgregion.app.AuthInfo
import se.vgregion.app.RestApi
import se.vgregion.app.UserProtocol.UserRole
import se.vgregion.dicom.DicomProtocol._

trait ScpRoutes { this: RestApi =>

def scpRoutes(authInfo: AuthInfo): Route =
    pathPrefix("scps") {
      pathEndOrSingleSlash {
        get {
          onSuccess(dicomService.ask(GetScps)) {
            case Scps(scps) =>
              complete(scps)
          }
        } ~ post {
          authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
            entity(as[AddScp]) { addScp =>
              onSuccess(dicomService.ask(addScp)) {
                case ScpAdded(scpData) =>
                  complete(scpData)
              }
            }
          }
        }
      } ~ path(LongNumber) { scpDataId =>
        delete {
          authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
            onSuccess(dicomService.ask(RemoveScp(scpDataId))) {
              case ScpRemoved(scpDataId) =>
                complete(NoContent)
            }
          }
        }
      }
    }

}