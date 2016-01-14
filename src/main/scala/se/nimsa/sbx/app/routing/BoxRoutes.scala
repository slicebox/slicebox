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
import se.nimsa.sbx.anonymization.AnonymizationProtocol.ImageTagValues
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.metadata.MetaDataProtocol.Images
import se.nimsa.sbx.user.UserProtocol.ApiUser
import se.nimsa.sbx.user.UserProtocol.UserRole
import spray.http.StatusCodes.Created
import spray.http.StatusCodes.NoContent
import spray.http.StatusCodes.NotFound
import spray.httpx.SprayJsonSupport._
import spray.routing.Route

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
            onSuccess(boxService.ask(GetBoxById(remoteBoxId)).mapTo[Option[Box]]) {
              _ match {
                case Some(box) =>
                  onSuccess(boxService.ask(SendToRemoteBox(box, imageTagValuesSeq))) {
                    case ImagesAddedToOutgoing(_, _) =>
                      complete(NoContent)
                  }
                case None =>
                  complete((NotFound, s"No box found for id $remoteBoxId"))
              }
            }
          }
        }
      } ~ incomingRoutes ~ outgoingRoutes
    }

  def incomingRoutes: Route =
    pathPrefix("incoming") {
      pathEndOrSingleSlash {
        get {
          onSuccess(boxService.ask(GetIncoming)) {
            case Incoming(entries) =>
              complete(entries)
          }
        }
      } ~ pathPrefix(LongNumber) { incomingEntryId =>
        pathEndOrSingleSlash {
          delete {
            onSuccess(boxService.ask(RemoveIncomingEntry(incomingEntryId))) {
              case IncomingEntryRemoved(incomingEntryId) =>
                complete(NoContent)
            }
          }
        } ~ path("images") {
          get {
            onSuccess(boxService.ask(GetImagesForIncomingEntry(incomingEntryId))) {
              case Images(images) =>
                complete(images)
            }
          }
        }
      }
    }

  def outgoingRoutes: Route =
    pathPrefix("outgoing") {
      pathEndOrSingleSlash {
        get {
          onSuccess(boxService.ask(GetOutgoing)) {
            case Outgoing(entries) =>
              complete(entries)
          }
        }
      } ~ pathPrefix(LongNumber) { outgoingEntryId =>
        pathEndOrSingleSlash {
          delete {
            onSuccess(boxService.ask(RemoveOutgoingEntry(outgoingEntryId))) {
              case OutgoingEntryRemoved(outgoingEntryId) =>
                complete(NoContent)
            }
          }
        } ~ path("images") {
          get {
            onSuccess(boxService.ask(GetImagesForOutgoingEntry(outgoingEntryId))) {
              case Images(images) =>
                complete(images)
            }
          }
        }
      }
    }

}
