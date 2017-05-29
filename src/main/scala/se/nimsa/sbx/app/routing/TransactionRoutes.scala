/*
 * Copyright 2017 Lars Edenbrandt
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

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.scaladsl.Compression
import akka.util.ByteString
import org.dcm4che3.data.Attributes
import org.dcm4che3.io.DicomStreamException
import se.nimsa.dcm4che.streams.DicomFlows.TagModification
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.streams.DicomStreams
import se.nimsa.sbx.dicom.streams.DicomStreams.{createTempPath, dicomDataSink}
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, GetImage, MetaDataAdded}
import se.nimsa.sbx.storage.StorageProtocol._

trait TransactionRoutes {
  this: SliceboxBase =>

  def transactionRoutes: Route =
    pathPrefix("transactions" / Segment) { token =>
      onSuccess(boxService.ask(GetBoxByToken(token)).mapTo[Option[Box]]) {
        case None =>
          complete((Unauthorized, s"No box found for token $token"))
        case Some(box) =>
          path("image") {
            parameters('transactionid.as[Long], 'sequencenumber.as[Long], 'totalimagecount.as[Long]) { (outgoingTransactionId, sequenceNumber, totalImageCount) =>
              post {
                extractDataBytes { compressedBytes =>
                  val source = Source(SourceType.BOX, box.name, box.id)
                  val tmpPath = createTempPath()

                  val futureStored = compressedBytes
                    .via(Compression.inflate())
                    .runWith(dicomDataSink(storage.fileSink(tmpPath), reverseAnonymizationQuery))

                  onSuccess(futureStored) {
                    case (_, dicomData) =>
                      val attributes: Attributes = dicomData._2.getOrElse(throw new DicomStreamException("DICOM data has no dataset"))
                      onSuccess(metaDataService.ask(AddMetaData(attributes, source)).mapTo[MetaDataAdded]) { metaData =>
                        onSuccess(storageService.ask(MoveDicomData(tmpPath, s"${metaData.image.id}")).mapTo[DicomDataMoved]) { _ =>
                          onSuccess(boxService.ask(UpdateIncoming(box, outgoingTransactionId, sequenceNumber, totalImageCount, metaData.image.id, metaData.imageAdded))) {
                            case IncomingUpdated(transaction) =>
                              transaction.status match {
                                case TransactionStatus.FAILED => complete(InternalServerError)
                                case _ => complete(NoContent)
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
                  case Some(status) => complete(HttpEntity(status.toString))
                  case None => complete(NotFound)
                }
              } ~ put {
                entity(as[String]) { statusString =>
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
                onSuccess(boxService.ask(PollOutgoing(box)).mapTo[Option[OutgoingTransactionImage]]) {
                  case Some(outgoingTransactionImage) => complete(outgoingTransactionImage)
                  case None => complete(NotFound)
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
                parameters('transactionid.as[Long], 'imageid.as[Long]) { (outgoingTransactionId, outgoingImageId) =>
                  onSuccess(boxService.ask(GetOutgoingTransactionImage(box, outgoingTransactionId, outgoingImageId)).mapTo[Option[OutgoingTransactionImage]]) {
                    case Some(transactionImage) =>
                      val imageId = transactionImage.image.imageId
                      onSuccess(boxService.ask(GetOutgoingTagValues(transactionImage)).mapTo[Seq[OutgoingTagValue]]) { transactionTagValues =>
                        val tagMods = transactionTagValues.map(ttv =>
                          TagModification(ttv.tagValue.tag, _ => ByteString(ttv.tagValue.value.getBytes("US-ASCII")), insert = true))
                        onSuccess(metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]) {
                          case Some(image) =>
                            val streamSource = DicomStreams.anonymizedDicomDataSource(storage.fileSource(image), anonymizationQuery, anonymizationInsert, tagMods)
                            complete(HttpEntity(ContentTypes.`application/octet-stream`, streamSource))
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
