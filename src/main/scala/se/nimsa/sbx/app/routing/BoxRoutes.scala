/*
 * Copyright 2015 Lars Edenbrandt
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
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.ImageTagValues
import se.nimsa.sbx.dicom.DicomUtil

trait BoxRoutes { this: SliceboxService =>

  def boxRoutes(apiUser: ApiUser): Route =
    pathPrefix("boxes") {
      pathEndOrSingleSlash {
        get {
          onSuccess(boxService.ask(GetBoxes)) {
            case Boxes(boxes) =>
              complete(boxes)
          }
        }
      } ~ authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
        path("createconnection") {
          post {
            entity(as[RemoteBoxConnectionData]) { remoteBoxConnectionData =>
              onSuccess(boxService.ask(CreateConnection(remoteBoxConnectionData))) {
                case RemoteBoxAdded(box) =>
                  complete((Created, box))
              }
            }
          }
        } ~ path("connect") {
          post {
            entity(as[RemoteBox]) { remoteBox =>
              onSuccess(boxService.ask(Connect(remoteBox))) {
                case RemoteBoxAdded(box) =>
                  complete((Created, box))
              }
            }
          }
        } ~ pathPrefix(LongNumber) { boxId =>
          pathEndOrSingleSlash {
            delete {
              onSuccess(boxService.ask(RemoveBox(boxId))) {
                case BoxRemoved(boxId) =>
                  complete(NoContent)
              }
            }
          }
        }
      } ~ path(LongNumber / "send") { remoteBoxId =>
        post {
          entity(as[Seq[ImageTagValues]]) { imageTagValuesSeq =>
            onSuccess(boxService.ask(SendToRemoteBox(remoteBoxId, imageTagValuesSeq))) {
              case ImagesAddedToOutbox(remoteBoxId, imageIds) => complete(NoContent)
              case BoxNotFound                                => complete(NotFound)
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
      } ~ pathPrefix(LongNumber) { inboxEntryId =>
        pathEndOrSingleSlash {
          delete {
            onSuccess(boxService.ask(RemoveInboxEntry(inboxEntryId))) {
              case InboxEntryRemoved(inboxEntryId) =>
                complete(NoContent)
            }
          }
        } ~ path("images") {
          get {
            onSuccess(boxService.ask(GetImagesForInboxEntry(inboxEntryId))) {
              case Images(images) =>
                complete(images)
            }
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

  def sentRoutes: Route =
    pathPrefix("sent") {
      pathEndOrSingleSlash {
        get {
          onSuccess(boxService.ask(GetSent)) {
            case Sent(entries) =>
              complete(entries)
          }
        }
      } ~ pathPrefix(LongNumber) { sentEntryId =>
        pathEndOrSingleSlash {
          delete {
            onSuccess(boxService.ask(RemoveSentEntry(sentEntryId))) {
              case SentEntryRemoved(sentEntryId) =>
                complete(NoContent)
            }
          }
        } ~ path("images") {
          get {
            onSuccess(boxService.ask(GetImagesForSentEntry(sentEntryId))) {
              case Images(images) =>
                complete(images)
            }
          }
        }
      }
    }

}
