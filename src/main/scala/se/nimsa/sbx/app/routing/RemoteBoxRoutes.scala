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
import spray.routing._
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.user.UserProtocol.UserRole
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.anonymization.AnonymizationUtil._
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import org.dcm4che3.data.Attributes
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling.BasicUnmarshallers.ByteArrayUnmarshaller
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.util.CompressionUtil._

trait RemoteBoxRoutes { this: SliceboxService =>

  def remoteBoxRoutes: Route =
    pathPrefix("box") {
      pathPrefix(Segment) { token =>

        onSuccess(boxService.ask(GetBoxByToken(token)).mapTo[Option[Box]]) {
          case None =>
            complete((Unauthorized, "Invalid token"))
          case Some(box) =>
            path("image") {
              parameters('transactionid.as[Long], 'sequencenumber.as[Long], 'totalimagecount.as[Long]) { (transactionId, sequenceNumber, totalImageCount) =>
                post {
                  entity(as[Array[Byte]]) { compressedBytes =>
                    val bytes = decompress(compressedBytes)
                    val dataset = loadDataset(bytes, true)
                    if (dataset == null)
                      complete((BadRequest, "Dataset could not be read"))
                    else
                      onSuccess(anonymizationService.ask(ReverseAnonymization(dataset)).mapTo[Attributes]) { reversedDataset =>
                        val source = Source(SourceType.BOX, box.name, box.id)
                        onSuccess(storageService.ask(AddDataset(reversedDataset, source))) {
                          case ImageAdded(image, source) =>
                            onSuccess(boxService.ask(UpdateInbox(token, transactionId, sequenceNumber, totalImageCount, image.id))) {
                              case msg: InboxUpdated =>
                                complete(NoContent)
                            }
                        }
                      }
                  }
                }
              }
            } ~ pathPrefix("outbox") {
              path("poll") {
                get {
                  onSuccess(boxService.ask(PollOutbox(token))) {
                    case outboxEntry: OutboxEntry =>
                      complete(outboxEntry)
                    case OutboxEmpty =>
                      complete(NotFound)
                  }
                }
              } ~ path("done") {
                post {
                  entity(as[OutboxEntry]) { outboxEntry =>
                    onSuccess(boxService.ask(DeleteOutboxEntry(token, outboxEntry.transactionId, outboxEntry.sequenceNumber))) {
                      case OutboxEntryDeleted => complete(NoContent)
                    }
                  }
                }
              } ~ path("failed") {
                post {
                  entity(as[FailedOutboxEntry]) { failedOutboxEntry =>
                    onSuccess(boxService.ask(MarkOutboxTransactionAsFailed(token, failedOutboxEntry.outboxEntry.transactionId, failedOutboxEntry.message))) {
                      case OutboxTransactionMarkedAsFailed => complete(NoContent)
                    }
                  }
                }
              } ~ pathEndOrSingleSlash {
                get {
                  parameters('transactionid.as[Long], 'sequencenumber.as[Long]) { (transactionId, sequenceNumber) =>
                    onSuccess(boxService.ask(GetOutboxEntry(token, transactionId, sequenceNumber))) {
                      case outboxEntry: OutboxEntry =>
                        onSuccess(boxService.ask(GetTransactionTagValues(outboxEntry.imageId, transactionId)).mapTo[Seq[TransactionTagValue]]) {
                          case transactionTagValues =>
                            onSuccess(storageService.ask(GetDataset(outboxEntry.imageId, true)).mapTo[Option[Attributes]]) {
                              _ match {
                                case Some(dataset) =>

                                  onSuccess(anonymizationService.ask(Anonymize(outboxEntry.imageId, dataset, transactionTagValues.map(_.tagValue)))) {
                                    case anonymizedDataset: Attributes =>
                                      val compressedBytes = compress(toByteArray(anonymizedDataset))
                                      complete(HttpEntity(ContentTypes.`application/octet-stream`, HttpData(compressedBytes)))
                                  }
                                case None =>

                                  complete((NotFound, s"File not found for image id ${outboxEntry.imageId}"))
                              }
                            }
                        }
                      case OutboxEntryNotFound =>
                        complete(NotFound)
                    }
                  }
                }
              }
            }
        }
      }
    }

}
