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
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.anonymization.AnonymizationUtil
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.{DicomData, DicomUtil}
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.user.UserProtocol.ApiUser
import se.nimsa.sbx.util.SbxExtensions._
import spray.http.{HttpData, HttpEntity}
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing.Route

import scala.concurrent.Future

trait AnonymizationRoutes {
  this: SliceboxService =>

  def anonymizationRoutes(apiUser: ApiUser): Route =
    path("images" / LongNumber / "anonymize") { imageId =>
      put {
        entity(as[Seq[TagValue]]) { tagValues =>
          complete {
            anonymizeOne(apiUser, imageId, tagValues).map(_.map(_.image))
          }
        }
      }
    } ~ path("images" / LongNumber / "anonymized") { imageId =>
      post {
        entity(as[Seq[TagValue]]) { tagValues =>
          onSuccess(metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]) {
            case Some(image) =>
              onSuccess(storageService.ask(GetDicomData(image, withPixelData = true, useBulkDataURI = false)).mapTo[Option[DicomData]]) {
                case Some(dicomData) =>
                  onSuccess(anonymizationService.ask(Anonymize(imageId, dicomData.attributes, tagValues)).mapTo[Attributes]) { anonAttributes =>
                    complete(HttpEntity(`application/octet-stream`, HttpData(DicomUtil.toByteArray(dicomData.copy(attributes = anonAttributes)))))
                  }
                case None =>
                  complete((NotFound, s"No image data found for image id $imageId"))
              }
            case None =>
              complete((NotFound, s"No image meta data found for image id $imageId"))
          }
        }
      }
    } ~ pathPrefix("anonymization") {
      path("anonymize") {
        post {
          entity(as[Seq[ImageTagValues]]) { imageTagValuesSeq =>
            complete {
              Future.sequence {
                imageTagValuesSeq.map(imageTagValues =>
                  anonymizeOne(apiUser, imageTagValues.imageId, imageTagValues.tagValues))
              }.map(_.flatMap(_.map(_.image)))
            }
          }
        }
      } ~ pathPrefix("keys") {
        pathEndOrSingleSlash {
          get {
            parameters(
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'orderby.as[String].?,
              'orderascending.as[Boolean] ? true,
              'filter.as[String].?) { (startIndex, count, orderBy, orderAscending, filter) =>
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
              complete(anonymizationService.ask(GetImageIdsForAnonymizationKey(anonymizationKeyId)).mapTo[List[Long]].flatMap { imageIds =>
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
              complete(anonymizationService.ask(QueryAnonymizationKeys(query)).mapTo[List[AnonymizationKey]])
            }
          }
        }
      }
    }

  def anonymizeOne(apiUser: ApiUser, imageId: Long, tagValues: Seq[TagValue]): Future[Option[DicomDataAdded]] =
    metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]].flatMap { imageMaybe =>
      imageMaybe.map { image =>
        metaDataService.ask(GetSourceForSeries(image.seriesId)).mapTo[Option[SeriesSource]].flatMap { seriesSourceMaybe =>
          seriesSourceMaybe.map { seriesSource =>
            val source = seriesSource.source
            storageService.ask(GetDicomData(image, withPixelData = true)).mapTo[Option[DicomData]].map { optionalDicomData =>
              optionalDicomData.map { dicomData =>
                AnonymizationUtil.setAnonymous(dicomData.attributes, anonymous = false) // pretend not anonymized to force anonymization
                anonymizationService.ask(Anonymize(imageId, dicomData.attributes, tagValues)).mapTo[Attributes].flatMap { anonAttributes =>
                  metaDataService.ask(DeleteMetaData(image.id)).flatMap { _ =>
                    storageService.ask(DeleteDicomData(image)).flatMap { _ =>
                      metaDataService.ask(AddMetaData(anonAttributes, source)).mapTo[MetaDataAdded].flatMap { metaData =>
                        storageService.ask(AddDicomData(dicomData.copy(attributes = anonAttributes), source, metaData.image)).mapTo[DicomDataAdded]
                      }
                    }
                  }
                }
              }
            }.unwrap
          }.unwrap
        }
      }.unwrap
    }

}
