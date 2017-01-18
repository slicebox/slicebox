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
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.metadata.MetaDataProtocol.{GetImage, Images}
import se.nimsa.sbx.user.UserProtocol.ApiUser
import se.nimsa.sbx.user.UserProtocol.UserRole
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

import scala.concurrent.Future

trait BoxRoutes {
  this: SliceboxBase =>

  def boxRoutes(apiUser: ApiUser): Route =
    pathPrefix("boxes") {
      pathEndOrSingleSlash {
        get {
          parameters(
            'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
            'count.as(nonNegativeFromStringUnmarshaller) ? 20) { (startIndex, count) =>
            onSuccess(boxService.ask(GetBoxes(startIndex, count))) {
              case Boxes(boxes) =>
                complete(boxes)
            }
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
                case BoxRemoved(_) =>
                  complete(NoContent)
              }
            }
          }
        }
      } ~ path(LongNumber / "send") { boxId =>
        post {
          entity(as[Seq[ImageTagValues]]) { imageTagValuesSeq =>
            onSuccess(boxService.ask(GetBoxById(boxId)).mapTo[Option[Box]]) {
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
      } ~ incomingRoutes ~ outgoingRoutes
    }

  def incomingRoutes: Route =
    pathPrefix("incoming") {
      pathEndOrSingleSlash {
        get {
          parameters(
            'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
            'count.as(nonNegativeFromStringUnmarshaller) ? 20) { (startIndex, count) =>
            onSuccess(boxService.ask(GetIncomingTransactions(startIndex, count))) {
              case IncomingTransactions(transactions) =>
                complete(transactions)
            }
          }
        }
      } ~ pathPrefix(LongNumber) { incomingTransactionId =>
        pathEndOrSingleSlash {
          delete {
            onSuccess(boxService.ask(RemoveIncomingTransaction(incomingTransactionId))) {
              case IncomingTransactionRemoved(_) =>
                complete(NoContent)
            }
          }
        } ~ path("images") {
          get {
            complete(boxService.ask(GetImageIdsForIncomingTransaction(incomingTransactionId)).mapTo[Seq[Long]].flatMap { imageIds =>
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
          parameters(
            'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
            'count.as(nonNegativeFromStringUnmarshaller) ? 20) { (startIndex, count) =>
            onSuccess(boxService.ask(GetOutgoingTransactions(startIndex, count))) {
              case OutgoingTransactions(transactions) =>
                complete(transactions)
            }
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
            complete(boxService.ask(GetImageIdsForOutgoingTransaction(outgoingTransactionId)).mapTo[Seq[Long]].flatMap { imageIds =>
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
