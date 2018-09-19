/*
 * Copyright 2014 Lars Edenbrandt
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

import akka.http.scaladsl.model.StatusCodes.{BadRequest, NoContent, NotFound, Unauthorized}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.scaladsl.Compression
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.Contexts
import se.nimsa.sbx.log.SbxLog

import scala.util.{Failure, Success}

trait TransactionRoutes {
  this: SliceboxBase =>

  def transactionRoutes: Route =
    pathPrefix("transactions" / Segment) { token =>
      onSuccess(boxService.ask(GetBoxByToken(token)).mapTo[Option[Box]]) {
        case None =>
          complete((Unauthorized, s"No box found for token $token"))
        case Some(box) =>
          path("image") {
            parameters((
              'transactionid.as[Long],
              'sequencenumber.as[Long],
              'totalimagecount.as[Long])) { (outgoingTransactionId, sequenceNumber, totalImageCount) =>
              post {
                withoutSizeLimit {
                  extractDataBytes { compressedBytes =>
                    val source = Source(SourceType.BOX, box.name, box.id)
                    onComplete(storeDicomData(compressedBytes.via(Compression.inflate()), source, storage, Contexts.extendedContexts, reverseAnonymization = true)) {
                      case Success(metaData) =>
                        system.eventStream.publish(ImageAdded(metaData.image.id, source, !metaData.imageAdded))
                        onSuccess(boxService.ask(UpdateIncoming(box, outgoingTransactionId, sequenceNumber, totalImageCount, Some(metaData.image.id), metaData.imageAdded))) {
                          _ => complete(NoContent)
                        }
                      case Failure(e) =>
                        SbxLog.warn("Box", s"Ignoring rejected image from ${box.name} in transaction $outgoingTransactionId: ${e.getMessage}")
                        onSuccess(boxService.ask(UpdateIncoming(box, outgoingTransactionId, sequenceNumber, totalImageCount, None, added = false))) {
                          _ => complete(NoContent)
                        }
                    }
                  }
                }
              }
            }
          } ~ path("status") {
            parameters('transactionid.as[Long]) { outgoingTransactionId =>
              get {
                onSuccess(boxService.ask(GetIncomingTransactionStatus(box, outgoingTransactionId)).mapTo[Option[TransactionStatus]]) {
                  case Some(status) => complete(BoxTransactionStatus(status))
                  case None => complete(NotFound)
                }
              } ~ put {
                entity(as[BoxTransactionStatus]) { boxTransactionStatus =>
                  val status = boxTransactionStatus.status
                  status match {
                    case TransactionStatus.UNKNOWN => complete((BadRequest, s"Invalid status format: $status"))
                    case _ =>
                      onSuccess(boxService.ask(SetIncomingTransactionStatus(box, outgoingTransactionId, status)).mapTo[Option[Unit]]) {
                        case Some(_) => complete(NoContent)
                        case None => complete(NotFound)
                      }
                  }
                }
              }
            }
          } ~ pathPrefix("outgoing") {
            path("poll") {
              parameter("n".as[Long].?(1L)) { n =>
                get {
                  onSuccess(boxService.ask(PollOutgoing(box, n)).mapTo[Seq[OutgoingTransactionImage]]) { transactionImages =>
                    if (transactionImages.isEmpty)
                      complete(NotFound)
                    else if (transactionImages.lengthCompare(1) == 0)
                      complete(transactionImages.head)
                    else
                      complete(transactionImages)
                  }
                }
              }
            } ~ path("done") {
              post {
                entity(as[OutgoingTransactionImage]) { transactionImage =>
                  onSuccess(boxService.ask(MarkOutgoingImageAsSent(box, transactionImage))) {
                    case OutgoingImageMarkedAsSent => complete(NoContent)
                  }
                }
              }
            } ~ path("failed") {
              post {
                entity(as[FailedOutgoingTransactionImage]) { failedTransactionImage =>
                  onSuccess(boxService.ask(MarkOutgoingTransactionAsFailed(box, failedTransactionImage))) {
                    case OutgoingTransactionMarkedAsFailed => complete(NoContent)
                  }
                }
              }
            } ~ pathEndOrSingleSlash {
              get {
                parameters(('transactionid.as[Long], 'imageid.as[Long])) { (outgoingTransactionId, outgoingImageId) =>
                  onSuccess(boxService.ask(GetOutgoingTransactionImage(box, outgoingTransactionId, outgoingImageId)).mapTo[Option[OutgoingTransactionImage]]) {
                    case Some(transactionImage) =>
                      val imageId = transactionImage.image.imageId
                      onSuccess(boxService.ask(GetOutgoingTagValues(transactionImage)).mapTo[Seq[OutgoingTagValue]]) { transactionTagValues =>
                        val tagValues = transactionTagValues.map(_.tagValue)
                        val streamSource = anonymizedDicomData(imageId, tagValues, storage)
                          .via(Compression.deflate)
                          .batchWeighted(storage.streamChunkSize, _.length, identity)(_ ++ _)
                        complete(HttpEntity(ContentTypes.`application/octet-stream`, streamSource))
                      }
                    case None =>
                      complete((NotFound, s"No outgoing image found for transaction id $outgoingTransactionId and outgoing image id $outgoingImageId"))
                  }
                }
              }
            }
          }
      }
    }

}
