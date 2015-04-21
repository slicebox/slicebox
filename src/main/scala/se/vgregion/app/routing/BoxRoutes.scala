package se.vgregion.app.routing

import akka.pattern.ask

import spray.http.ContentTypes
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.vgregion.app.AuthInfo
import se.vgregion.app.RestApi
import se.vgregion.app.UserProtocol.UserRole
import se.vgregion.box.BoxProtocol._
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.dicom.DicomUtil

trait BoxRoutes { this: RestApi =>

  def boxRoutes(authInfo: AuthInfo): Route =
    pathPrefix("boxes") {
      pathEndOrSingleSlash {
        get {
          onSuccess(boxService.ask(GetBoxes)) {
            case Boxes(boxes) =>
              complete(boxes)
          }
        }
      } ~ authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
        path("generatebaseurl") {
          post {
            entity(as[RemoteBoxName]) { remoteBoxName =>
              onSuccess(boxService.ask(GenerateBoxBaseUrl(remoteBoxName.value))) {
                case BoxBaseUrlGenerated(baseUrl) =>
                  complete((Created, BoxBaseUrl(baseUrl)))
              }
            }
          }
        } ~ path("addremotebox") {
          post {
            entity(as[RemoteBox]) { remoteBox =>
              onSuccess(boxService.ask(AddRemoteBox(remoteBox))) {
                case RemoteBoxAdded(box) =>
                  complete((Created, box))
              }
            }
          }
        } ~ path(LongNumber) { boxId =>
          delete {
            onSuccess(boxService.ask(RemoveBox(boxId))) {
              case BoxRemoved(boxId) =>
                complete(NoContent)
            }
          }
        }
      } ~ path(LongNumber / "sendpatients") { remoteBoxId =>
        post {
          entity(as[BoxSendData]) { patientSendData =>
            onSuccess(boxService.ask(SendPatientsToRemoteBox(remoteBoxId, patientSendData.entityIds, patientSendData.tagValues))) {
              case ImagesSent(remoteBoxId, imageIds) => complete(NoContent)
              case BoxNotFound                       => complete(NotFound)
            }
          }
        }
      } ~ path(LongNumber / "sendstudies") { remoteBoxId =>
        post {
          entity(as[BoxSendData]) { studySendData =>
            onSuccess(boxService.ask(SendStudiesToRemoteBox(remoteBoxId, studySendData.entityIds, studySendData.tagValues))) {
              case ImagesSent(remoteBoxId, imageIds) => complete(NoContent)
              case BoxNotFound                       => complete(NotFound)
            }
          }
        }
      } ~ path(LongNumber / "sendseries") { remoteBoxId =>
        post {
          entity(as[BoxSendData]) { seriesSendData =>
            onSuccess(boxService.ask(SendSeriesToRemoteBox(remoteBoxId, seriesSendData.entityIds, seriesSendData.tagValues))) {
              case ImagesSent(remoteBoxId, imageIds) => complete(NoContent)
              case BoxNotFound                       => complete(NotFound)
            }
          }
        }
      }
    }

  def inboxRoutes: Route =
    pathPrefix("inbox") {
      pathEndOrSingleSlash {
        get {
          onSuccess(boxService.ask(GetInbox)) {
            case Inbox(entries) =>
              complete(entries)
          }
        }
      }
    }

  def outboxRoutes: Route =
    pathPrefix("outbox") {
      pathEndOrSingleSlash {
        get {
          onSuccess(boxService.ask(GetOutbox)) {
            case Outbox(entries) =>
              complete(entries)
          }
        }
      } ~ path(LongNumber) { outboxEntryId =>
        delete {
          onSuccess(boxService.ask(RemoveOutboxEntry(outboxEntryId))) {
            case OutboxEntryRemoved(outboxEntryId) =>
              complete(NoContent)
          }
        }
      }
    }

}