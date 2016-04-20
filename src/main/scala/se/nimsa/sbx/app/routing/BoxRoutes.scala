/*
 * Copyright 2016 Lars Edenbrandt
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
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.metadata.MetaDataProtocol.{GetImage, Images}
import se.nimsa.sbx.user.UserProtocol.ApiUser
import se.nimsa.sbx.user.UserProtocol.UserRole
import spray.http.StatusCodes.Created
import spray.http.StatusCodes.NoContent
import spray.http.StatusCodes.NotFound
import spray.httpx.SprayJsonSupport._
import spray.routing.Route

import scala.concurrent.Future

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
      } ~ path(LongNumber / "send") { boxId =>
        post {
          entity(as[Seq[ImageTagValues]]) { imageTagValuesSeq =>
            onSuccess(boxService.ask(GetBoxById(boxId)).mapTo[Option[Box]]) {
              _ match {
                case Some(box) =>
                  onSuccess(boxService.ask(SendToRemoteBox(box, imageTagValuesSeq))) {
                    case ImagesAddedToOutgoing(_, _) =>
                      complete(NoContent)
                  }
                case None =>
                  complete((NotFound, s"No box found for id $boxId"))
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
          onSuccess(boxService.ask(GetIncomingTransactions)) {
            case IncomingTransactions(transactions) =>
              complete(transactions)
          }
        }
      } ~ pathPrefix(LongNumber) { incomingTransactionId =>
        pathEndOrSingleSlash {
          delete {
            onSuccess(boxService.ask(RemoveIncomingTransaction(incomingTransactionId))) {
              case IncomingTransactionRemoved(incomingTransactionId) =>
                complete(NoContent)
            }
          }
        } ~ path("images") {
          get {
            complete(boxService.ask(GetImageIdsForIncomingTransaction(incomingTransactionId)).mapTo[List[Long]].flatMap { imageIds =>
              Future.sequence {
                imageIds.map { imageId =>
                  metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]
                }
              }
            }.map(_.flatten))
          }
        }
      }
    }

  def outgoingRoutes: Route =
    pathPrefix("outgoing") {
      pathEndOrSingleSlash {
        get {
          onSuccess(boxService.ask(GetOutgoingTransactions)) {
            case OutgoingTransactions(transactions) =>
              complete(transactions)
          }
        }
      } ~ pathPrefix(LongNumber) { outgoingTransactionId =>
        pathEndOrSingleSlash {
          delete {
            onSuccess(boxService.ask(RemoveOutgoingTransaction(outgoingTransactionId))) {
              case OutgoingTransactionRemoved(outgoingEntryId) =>
                complete(NoContent)
            }
          }
        } ~ path("images") {
          get {
            complete(boxService.ask(GetImageIdsForOutgoingTransaction(outgoingTransactionId)).mapTo[List[Long]].flatMap { imageIds =>
              Future.sequence {
                imageIds.map { imageId =>
                  metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]
                }
              }
            }.map(_.flatten))
          }
        }
      }
    }

}
