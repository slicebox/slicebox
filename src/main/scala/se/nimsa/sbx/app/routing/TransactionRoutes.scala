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
import spray.http.ContentTypes
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.StatusCodes._
import spray.routing._
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import org.dcm4che3.data.Attributes
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling.BasicUnmarshallers.ByteArrayUnmarshaller
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, GetImage, MetaDataAdded}
import se.nimsa.sbx.util.CompressionUtil._

trait TransactionRoutes {
  this: SliceboxService =>

  def entityAsString = extract { _.request.entity.asString }

  def transactionRoutes: Route =
    pathPrefix("transactions" / Segment) { token =>

      onSuccess(boxService.ask(GetBoxByToken(token)).mapTo[Option[Box]]) {
        case None =>
          complete((Unauthorized, s"No box found for token $token"))
        case Some(box) =>
          path("image") {
            parameters('transactionid.as[Long], 'sequencenumber.as[Long], 'totalimagecount.as[Long]) { (outgoingTransactionId, sequenceNumber, totalImageCount) =>
              post {
                entity(as[Array[Byte]]) { compressedBytes =>
                  val bytes = decompress(compressedBytes)
                  val dataset = loadDataset(bytes, withPixelData = true, useBulkDataURI = false)
                  if (dataset == null)
                    complete((BadRequest, "Dataset could not be read"))
                  else
                    onSuccess(storageService.ask(CheckDataset(dataset, restrictSopClass = false)).mapTo[Boolean]) { status =>
                      onSuccess(anonymizationService.ask(ReverseAnonymization(dataset)).mapTo[Attributes]) { reversedDataset =>
                        val source = Source(SourceType.BOX, box.name, box.id)
                        onSuccess(metaDataService.ask(AddMetaData(reversedDataset, source)).mapTo[MetaDataAdded]) { metaData =>
                          onSuccess(storageService.ask(AddDataset(reversedDataset, source, metaData.image))) {
                            case DatasetAdded(image, overwrite) =>
                              onSuccess(boxService.ask(UpdateIncoming(box, outgoingTransactionId, sequenceNumber, totalImageCount, image.id, overwrite))) {
                                case IncomingUpdated(_) => complete(NoContent)
                              }
                          }
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
                  case Some(status) => complete(status.toString)
                  case None => complete(NotFound)
                }
              } ~ put {
                entityAsString { statusString =>
                  val status = TransactionStatus.withName(statusString)
                  status match {
                    case TransactionStatus.UNKNOWN => complete((BadRequest, s"Invalid status format: $statusString"))
                    case _ =>
                      onSuccess(boxService.ask(SetIncomingTransactionStatus(box.id, outgoingTransactionId, status)).mapTo[Option[Unit]]) {
                        case Some(_) => complete(NoContent)
                        case None => complete(NotFound)
                      }
                  }
                }
              }
            }
          } ~ pathPrefix("outgoing") {
            path("poll") {
              get {
                complete(boxService.ask(PollOutgoing(box)).mapTo[Option[OutgoingTransactionImage]])
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
                parameters('transactionid.as[Long], 'imageid.as[Long]) { (outgoingTransactionId, outgoingImageId) =>
                  onSuccess(boxService.ask(GetOutgoingTransactionImage(box, outgoingTransactionId, outgoingImageId)).mapTo[Option[OutgoingTransactionImage]]) {
                    case Some(transactionImage) =>
                      val imageId = transactionImage.image.imageId
                      onSuccess(boxService.ask(GetOutgoingTagValues(transactionImage)).mapTo[Seq[OutgoingTagValue]]) {
                        case transactionTagValues =>
                          onSuccess(metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]) {
                            case Some(image) =>
                              onSuccess(storageService.ask(GetDataset(image, withPixelData = true)).mapTo[Option[Attributes]]) {
                                case Some(dataset) =>

                                  onSuccess(anonymizationService.ask(Anonymize(imageId, dataset, transactionTagValues.map(_.tagValue)))) {
                                    case anonymizedDataset: Attributes =>
                                      val compressedBytes = compress(toByteArray(anonymizedDataset))
                                      complete(HttpEntity(ContentTypes.`application/octet-stream`, HttpData(compressedBytes)))
                                  }
                                case None =>
                                  complete((NotFound, s"File not found for image id $imageId"))
                              }
                            case None =>
                              complete((NotFound, s"Image not found for image id $imageId"))
                          }
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
