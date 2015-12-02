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

import java.io.File

import scala.concurrent.Future

import org.dcm4che3.data.Attributes

import akka.pattern.ask
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.anonymization.AnonymizationUtil
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.dicom.ImageAttribute
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.user.UserProtocol.ApiUser
import se.nimsa.sbx.util.SbxExtensions._
import spray.http.ContentType.apply
import spray.http.FormFile
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.HttpHeaders._
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.routing.Route
import spray.routing.directives._
import spray.httpx.SprayJsonSupport._

trait AnonymizationRoutes { this: SliceboxService =>

  def anonymizationRoutes(apiUser: ApiUser): Route =
    path("images" / LongNumber / "anonymize") { imageId =>
      put {
        entity(as[Seq[TagValue]]) { tagValues =>
          onSuccess(anonymizeOne(apiUser, imageId, tagValues)) {
            case Some(_) => complete(NoContent)
            case None    => complete(NotFound)
          }
        }
      }
    } ~ pathPrefix("anonymization") {
      path("anonymize") {
        post {
          entity(as[Seq[AnonymizationParameters]]) { anonParams =>
            onSuccess {
              Future.sequence {
                anonParams.map(param => anonymizeOne(apiUser, param.imageId, param.tagValues))
              }
            } { result =>
              result match {
                case result if result.forall(_.isEmpty) => complete(NotFound)
                case result                             => complete(NoContent)
              }
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
                    import spray.httpx.SprayJsonSupport._
                    complete(anonymizationKeys)
                }
              }
          }
        } ~ path(LongNumber) { anonymizationKeyId =>
          delete {
            onSuccess(anonymizationService.ask(RemoveAnonymizationKey(anonymizationKeyId))) {
              case AnonymizationKeyRemoved(anonymizationKeyId) =>
                complete(NoContent)
            }
          }
        }
      }
    }

  def anonymizeOne(apiUser: ApiUser, imageId: Long, tagValues: Seq[TagValue]) =
    storageService.ask(GetDataset(imageId, true)).mapTo[Option[Attributes]].map { optionalDataset =>
      optionalDataset.map { dataset =>
        AnonymizationUtil.setAnonymous(dataset, false) // pretend not anonymized to force anonymization
        anonymizationService.ask(Anonymize(imageId, dataset, tagValues)).flatMap {
          case anonDataset: Attributes =>
            storageService.ask(DeleteImage(imageId)).flatMap {
              case ImageDeleted(imageId) =>
                val source = Source(SourceType.USER, apiUser.user, apiUser.id)
                storageService.ask(AddDataset(anonDataset, source)).mapTo[ImageAdded]
            }
        }
      }
    }.unwrap

}
