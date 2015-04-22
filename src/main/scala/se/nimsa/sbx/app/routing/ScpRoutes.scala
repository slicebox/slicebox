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
                case scpData: ScpData =>
                  complete((Created, scpData))
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