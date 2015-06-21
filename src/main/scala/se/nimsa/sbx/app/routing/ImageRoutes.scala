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
import spray.http.FormFile
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.MediaTypes
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.NotFound
import spray.http.StatusCodes.Created
import spray.http.StatusCodes.NoContent
import spray.routing._
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.app.AuthInfo
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import org.dcm4che3.data.Attributes

trait ImageRoutes { this: RestApi =>

  def imageRoutes(authInfo: AuthInfo): Route =
    pathPrefix("images") {
      pathEndOrSingleSlash {
        post {
          formField('file.as[FormFile]) { file =>
            val dataset = DicomUtil.loadDataset(file.entity.data.toByteArray, true)
            onSuccess(storageService.ask(AddDataset(dataset, SourceType.USER, authInfo.user.id))) {
              case ImageAdded(image) =>
                import spray.httpx.SprayJsonSupport._
                complete((Created, image))
            }
          } ~ entity(as[Array[Byte]]) { bytes =>
            val dataset = DicomUtil.loadDataset(bytes, true)
            onSuccess(storageService.ask(AddDataset(dataset, SourceType.USER, authInfo.user.id))) {
              case ImageAdded(image) =>
                import spray.httpx.SprayJsonSupport._
                complete((Created, image))
            }
          }
        }
      } ~ path(LongNumber) { imageId =>
        get {
          onSuccess(storageService.ask(GetImageFile(imageId)).mapTo[Option[ImageFile]]) {
            case imageFileMaybe => imageFileMaybe.map(imageFile => {
              val file = storage.resolve(imageFile.fileName.value).toFile
              if (file.isFile && file.canRead)
                detach() {
                  complete(HttpEntity(ContentTypes.`application/octet-stream`, HttpData(file)))
                }
              else
                complete((BadRequest, "Dataset could not be read"))
            }).getOrElse {
              complete((NotFound, s"No file found for image id $imageId"))
            }
          }
        } ~ delete {
          onSuccess(storageService.ask(DeleteImage(imageId))) {
            case ImageDeleted(imageId) =>
              complete(NoContent)
          }
        }
      } ~ path(LongNumber / "attributes") { imageId =>
        get {
          onSuccess(storageService.ask(GetImageAttributes(imageId)).mapTo[Option[List[ImageAttribute]]]) {
            import spray.httpx.SprayJsonSupport._
            complete(_)
          }
        }
      } ~ path(LongNumber / "imageinformation") { imageId =>
        get {
          onSuccess(storageService.ask(GetImageInformation(imageId)).mapTo[Option[ImageInformation]]) {
            import spray.httpx.SprayJsonSupport._
            complete(_)
          }
        }
      } ~ path(LongNumber / "anonymize") { imageId =>
        post {
          import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
          entity(as[Seq[TagValue]]) { tagValues =>
            onSuccess(storageService.ask(GetImageFile(imageId)).mapTo[Option[ImageFile]]) {
              _ match {
                case Some(imageFile) =>
                  onSuccess(storageService.ask(GetDataset(imageId)).mapTo[Option[Attributes]]) {
                    _ match {

                      case Some(dataset) =>
                        onSuccess(anonymizationService.ask(Anonymize(dataset, tagValues)).mapTo[Attributes]) { anonDataset =>
                          onSuccess(storageService.ask(DeleteImage(imageId))) {
                            case ImageDeleted(imageId) =>
                              onSuccess(storageService.ask(AddDataset(anonDataset, imageFile.sourceType, imageFile.sourceId))) {
                                case ImageAdded(image) =>
                                  complete(NoContent)
                              }
                          }
                        }

                      case None =>
                        complete(NotFound)
                    }
                  }

                case None =>
                  complete(NotFound)
              }
            }
          }
        }
      } ~ path(LongNumber / "png") { imageId =>
        parameters(
          'framenumber.as[Int] ? 1,
          'windowmin.as[Int] ? 0,
          'windowmax.as[Int] ? 0,
          'imageheight.as[Int] ? 0) { (frameNumber, min, max, height) =>
            get {
              onSuccess(storageService.ask(GetImageFrame(imageId, frameNumber, min, max, height)).mapTo[Option[Array[Byte]]]) {
                _ match {
                  case Some(bytes) => complete(HttpEntity(MediaTypes.`image/png`, HttpData(bytes)))
                  case None        => complete(NotFound)
                }
              }
            }
          }
      } ~ pathPrefix("anonymizationkeys") {
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

}
