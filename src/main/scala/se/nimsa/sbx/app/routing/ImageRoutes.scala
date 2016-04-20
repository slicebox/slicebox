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

import java.io.File

import scala.concurrent.Future
import akka.pattern.ask
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.dicom.DicomHierarchy.{FlatSeries, Image, Patient, Study}
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.dicom.ImageAttribute
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.user.UserProtocol.ApiUser
import spray.http.ContentType.apply
import spray.http.FormFile
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.HttpHeaders._
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.routing.Route
import se.nimsa.sbx.util.SbxExtensions._

trait ImageRoutes {
  this: SliceboxService =>

  val chunkSize = 524288
  val bufferSize = chunkSize

  def imageRoutes(apiUser: ApiUser): Route =
    pathPrefix("images") {
      pathEndOrSingleSlash {
        post {
          formField('file.as[FormFile]) { file =>
            val dataset = DicomUtil.loadDataset(file.entity.data.toByteArray, withPixelData = true)
            val source = Source(SourceType.USER, apiUser.user, apiUser.id)
            onSuccess(storageService.ask(CheckDataset(dataset)).mapTo[Boolean]) { status =>
              onSuccess(metaDataService.ask(AddMetaData(dataset, source)).mapTo[MetaDataAdded]) { metaData =>
                onSuccess(storageService.ask(AddDataset(dataset, metaData.image))) {
                  case DatasetAdded(image, overwrite) =>
                    import spray.httpx.SprayJsonSupport._
                    if (overwrite)
                      complete((OK, image))
                    else
                      complete((Created, image))
                }
              }
            }
          } ~ entity(as[Array[Byte]]) { bytes =>
            val dataset = DicomUtil.loadDataset(bytes, withPixelData = true)
            val source = Source(SourceType.USER, apiUser.user, apiUser.id)
            onSuccess(storageService.ask(CheckDataset(dataset)).mapTo[Boolean]) { status =>
              onSuccess(metaDataService.ask(AddMetaData(dataset, source)).mapTo[MetaDataAdded]) { metaData =>
                onSuccess(storageService.ask(AddDataset(dataset, metaData.image))) {
                  case DatasetAdded(image, overwrite) =>
                    import spray.httpx.SprayJsonSupport._
                    if (overwrite)
                      complete((OK, image))
                    else
                      complete((Created, image))
                }
              }
            }
          }
        }
      } ~ pathPrefix(LongNumber) { imageId =>
        onSuccess(metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]) {
          case Some(image) =>
            pathEndOrSingleSlash {
              get {
                onSuccess(storageService.ask(GetImagePath(image)).mapTo[Option[ImagePath]]) {
                  _.map(imagePath => {
                    detach() {
                      autoChunk(chunkSize) {
                        complete(HttpEntity(`application/octet-stream`, HttpData(imagePath.imagePath.toFile)))
                      }
                    }
                  }).getOrElse {
                    complete((NotFound, s"No file found for image id $imageId"))
                  }
                }
              } ~ delete {
                onSuccess(storageService.ask(DeleteDataset(image))) {
                  case DatasetDeleted(image) =>
                    complete(NoContent)
                }
              }
            } ~ path("attributes") {
              get {
                onSuccess(storageService.ask(GetImageAttributes(image)).mapTo[Option[List[ImageAttribute]]]) {
                  import spray.httpx.SprayJsonSupport._
                  complete(_)
                }
              }
            } ~ path("imageinformation") {
              get {
                onSuccess(storageService.ask(GetImageInformation(image)).mapTo[Option[ImageInformation]]) {
                  import spray.httpx.SprayJsonSupport._
                  complete(_)
                }
              }
            } ~ path("png") {
              parameters(
                'framenumber.as[Int] ? 1,
                'windowmin.as[Int] ? 0,
                'windowmax.as[Int] ? 0,
                'imageheight.as[Int] ? 0) { (frameNumber, min, max, height) =>
                get {
                  onSuccess(storageService.ask(GetImageFrame(image, frameNumber, min, max, height)).mapTo[Option[Array[Byte]]]) {
                    case Some(bytes) => complete(HttpEntity(`image/png`, HttpData(bytes)))
                    case None => complete(NotFound)
                  }
                }
              }
            }
          case None =>
            complete(NotFound)
        }
      } ~ path("delete") {
        post {
          import spray.httpx.SprayJsonSupport._
          entity(as[Seq[Long]]) { imageIds =>
            val futureDeleted = Future.sequence {
              imageIds.map { imageId =>
                metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]].map { imageMaybe =>
                  imageMaybe.map { image =>
                    storageService.ask(DeleteDataset(image))
                  }
                }.unwrap
              }
            }
            onSuccess(futureDeleted) { m =>
              complete(NoContent)
            }
          }
        }
      } ~ path("export") {
        post {
          import spray.httpx.SprayJsonSupport._
          entity(as[Seq[Long]]) { imageIds =>
            if (imageIds.isEmpty)
              complete(NoContent)
            else {
              val imagesAndSeriesFuture = Future.sequence {
                imageIds.map { imageId =>
                  metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]].flatMap { imageMaybe =>
                    imageMaybe.map { image =>
                      metaDataService.ask(GetSingleFlatSeries(image.seriesId)).mapTo[Option[FlatSeries]].map { flatSeriesMaybe =>
                        flatSeriesMaybe.map { flatSeries =>
                          (image, flatSeries)
                        }
                      }
                    }.unwrap
                  }
                }
              }.map(_.flatten)

              onSuccess(imagesAndSeriesFuture) { imagesAndSeries =>
                complete(storageService.ask(CreateTempZipFile(imagesAndSeries)).mapTo[FileName])
              }
            }
          }
        } ~ get {
          parameter('filename) { fileName =>
            detach() {
              autoChunk(chunkSize) {
                respondWithHeader(`Content-Disposition`("attachment; filename=\"slicebox-export.zip\"")) {
                  complete(HttpEntity(`application/zip`, HttpData(new File(System.getProperty("java.io.tmpdir"), fileName))))
                }
              }
            }
          }
        }
      } ~ path("jpeg") {
        parameters('studyid.as[Long]) { studyId =>
          post {
            entity(as[Array[Byte]]) { jpegBytes =>
              val source = Source(SourceType.USER, apiUser.user, apiUser.id)
              val studyPatientFuture = metaDataService.ask(GetStudy(studyId)).mapTo[Option[Study]].flatMap { studyMaybe =>
                studyMaybe.map { study =>
                  metaDataService.ask(GetPatient(study.patientId)).mapTo[Option[Patient]].map { patientMaybe =>
                    patientMaybe.map { patient =>
                      (study, patient)
                    }
                  }
                }.unwrap
              }
              onSuccess(studyPatientFuture) {
                case Some((study, patient)) =>
                  onSuccess(storageService.ask(AddJpeg(jpegBytes, patient, study, source)).mapTo[Option[Image]]) {
                    case Some(image) =>
                      import spray.httpx.SprayJsonSupport._
                      complete((Created, image))
                    case _ =>
                      complete(NotFound)
                  }
                case _ =>
                  complete(NotFound)
              }
            }
          }
        }
      }
    }

}
