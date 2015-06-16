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
import se.nimsa.sbx.app.AuthInfo
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.app.UserProtocol.UserRole
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.box.BoxUtil._
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.anonymization.AnonymizationUtil._
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import org.dcm4che3.data.Attributes

trait RemoteBoxRoutes { this: RestApi =>

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
                  // make sure spray.httpx.SprayJsonSupport._ is NOT imported here. It messes with the content type expectations
                  entity(as[Array[Byte]]) { imageData =>
                    val dataset = loadDataset(imageData, true)
                    onSuccess(anonymizationService.ask(ReverseAnonymization(dataset))) {
                      case dataset: Attributes =>
                        onSuccess(storageService.ask(AddDataset(dataset, SourceType.BOX, box.id))) {
                          case ImageAdded(image) =>
                            onSuccess(boxService.ask(UpdateInbox(token, transactionId, sequenceNumber, totalImageCount))) {
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
                      import spray.httpx.SprayJsonSupport._
                      complete(outboxEntry)
                    case OutboxEmpty =>
                      complete(NotFound)
                  }
                }
              } ~ path("done") {
                post {
                  import spray.httpx.SprayJsonSupport._
                  entity(as[OutboxEntry]) { outboxEntry =>
                    onSuccess(boxService.ask(DeleteOutboxEntry(token, outboxEntry.transactionId, outboxEntry.sequenceNumber))) {
                      case OutboxEntryDeleted => complete(NoContent)
                    }
                  }
                }
              } ~ pathEndOrSingleSlash {
                get {
                  parameters('transactionid.as[Long], 'sequencenumber.as[Long]) { (transactionId, sequenceNumber) =>
                    onSuccess(boxService.ask(GetOutboxEntry(token, transactionId, sequenceNumber))) {
                      case outboxEntry: OutboxEntry =>
                        onSuccess(boxService.ask(GetTransactionTagValues(outboxEntry.imageFileId, transactionId)).mapTo[Seq[TransactionTagValue]]) {
                          case transactionTagValues =>
                            onSuccess(storageService.ask(GetImageFile(outboxEntry.imageFileId)).mapTo[Option[ImageFile]]) {
                              case imageFileMaybe => imageFileMaybe.map(imageFile => {
                                detach() {
                                  val path = storage.resolve(imageFile.fileName.value)

                                  val dataset = loadDataset(path, true)
                                  val anonymizedDataset = anonymizeDataset(dataset)

                                  applyTagValues(anonymizedDataset, transactionTagValues.map(_.tagValue))

                                  onSuccess(anonymizationService.ask(HarmonizeAnonymization(dataset, anonymizedDataset))) {
                                    case anonymizedDataset: Attributes =>
                                      val bytes = toByteArray(anonymizedDataset)
                                      complete(HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
                                  }
                                }
                              }).getOrElse {
                                complete((NotFound, s"File not found for image id ${outboxEntry.imageFileId}"))
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
