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

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.scaladsl.{Sink, Source}
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.{ImageAdded, ImagesDeleted}
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.user.UserProtocol.ApiUser

import scala.concurrent.Future

trait AnonymizationRoutes {
  this: SliceboxBase =>

  def anonymizationRoutes(apiUser: ApiUser): Route =
    path("images" / LongNumber / "anonymize") { imageId =>
      put {
        entity(as[Seq[TagValue]]) { tagValues =>
          onSuccess(anonymizeData(imageId, tagValues, storage)) {
            case Some(metaData) =>
              system.eventStream.publish(ImagesDeleted(Seq(imageId)))
              system.eventStream.publish(ImageAdded(metaData.image.id, metaData.source, !metaData.imageAdded))
              complete(metaData.image)
            case None =>
              complete((NotFound, s"No image meta data found for image id $imageId"))
          }
        }
      }
    } ~ path("images" / LongNumber / "anonymized") { imageId =>
      post {
        entity(as[Seq[TagValue]]) { tagValues =>
          val source = anonymizedDicomData(imageId, tagValues, storage)
          complete(HttpEntity(ContentTypes.`application/octet-stream`, source))
        }
      }
    } ~ pathPrefix("anonymization") {
      path("anonymize") {
        post {
          entity(as[Seq[ImageTagValues]]) { imageTagValuesSeq =>
            complete {
              Source.fromIterator(() => imageTagValuesSeq.iterator)
                .mapAsyncUnordered(8) { imageTagValues =>
                  anonymizeData(imageTagValues.imageId, imageTagValues.tagValues, storage)
                }
                .runWith(Sink.seq)
                .map { metaDataMaybes =>
                  system.eventStream.publish(ImagesDeleted(imageTagValuesSeq.map(_.imageId)))
                  metaDataMaybes.flatMap { metaDataMaybe =>
                    metaDataMaybe.map { metaData =>
                      system.eventStream.publish(ImageAdded(metaData.image.id, metaData.source, !metaData.imageAdded))
                      metaData.image
                    }
                  }
                }
            }
          }
        }
      } ~ pathPrefix("keys") {
        pathEndOrSingleSlash {
          get {
            parameters((
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'orderby.as[String].?,
              'orderascending.as[Boolean] ? true,
              'filter.as[String].?)) { (startIndex, count, orderBy, orderAscending, filter) =>
              onSuccess(anonymizationService.ask(GetAnonymizationKeys(startIndex, count, orderBy, orderAscending, filter))) {
                case AnonymizationKeys(anonymizationKeys) =>
                  complete(anonymizationKeys)
              }
            }
          }
        } ~ pathPrefix(LongNumber) { anonymizationKeyId =>
          pathEndOrSingleSlash {
            get {
              rejectEmptyResponse {
                complete(anonymizationService.ask(GetAnonymizationKey(anonymizationKeyId)).mapTo[Option[AnonymizationKey]])
              }
            } ~ delete {
              onSuccess(anonymizationService.ask(RemoveAnonymizationKey(anonymizationKeyId))) {
                case AnonymizationKeyRemoved(_) =>
                  complete(NoContent)
              }
            }
          } ~ path("images") {
            get {
              complete(anonymizationService.ask(GetImageIdsForAnonymizationKey(anonymizationKeyId)).mapTo[Seq[Long]].flatMap { imageIds =>
                Future.sequence {
                  imageIds.map { imageId =>
                    metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]
                  }
                }
              }.map(_.flatten))
            }
          }
        } ~ path("query") {
          post {
            entity(as[AnonymizationKeyQuery]) { query =>
              complete(anonymizationService.ask(QueryAnonymizationKeys(query)).mapTo[Seq[AnonymizationKey]])
            }
          }
        }
      }
    }

}
