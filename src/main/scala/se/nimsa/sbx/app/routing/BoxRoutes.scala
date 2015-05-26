/*
 * Copyright 2015 Karl Sjöstrand
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.app.routing

import akka.pattern.ask

import spray.http.ContentTypes
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.nimsa.sbx.app.AuthInfo
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.app.UserProtocol.UserRole
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomProtocol._
import se.nimsa.sbx.dicom.DicomUtil

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
      } ~ pathPrefix("anonymizationkeys") {
        pathEndOrSingleSlash {
          get {
            onSuccess(boxService.ask(GetAnonymizationKeys)) {
              case AnonymizationKeys(anonymizationKeys) =>
                complete(anonymizationKeys)
            }
          }
        } ~ path(LongNumber) { anonymizationKeyId =>
          delete {
            onSuccess(boxService.ask(RemoveAnonymizationKey(anonymizationKeyId))) {
              case AnonymizationKeyRemoved(anonymizationKeyId) =>
                complete(NoContent)
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
