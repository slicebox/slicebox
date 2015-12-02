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
import spray.http.ContentType.apply
import spray.http.FormFile
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.HttpHeaders._
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.routing.Route
import spray.routing.directives._

trait ImageRoutes { this: SliceboxService =>

  val chunkSize = 524288
  val bufferSize = chunkSize

  def imageRoutes(apiUser: ApiUser): Route =
    pathPrefix("images") {
      pathEndOrSingleSlash {
        post {
          formField('file.as[FormFile]) { file =>
            val dataset = DicomUtil.loadDataset(file.entity.data.toByteArray, true)
            val source = Source(SourceType.USER, apiUser.user, apiUser.id)
            onSuccess(storageService.ask(AddDataset(dataset, source))) {
              case ImageAdded(image, source) =>
                import spray.httpx.SprayJsonSupport._
                complete((Created, image))
            }
          } ~ entity(as[Array[Byte]]) { bytes =>
            val dataset = DicomUtil.loadDataset(bytes, true)
            val source = Source(SourceType.USER, apiUser.user, apiUser.id)
            onSuccess(storageService.ask(AddDataset(dataset, source))) {
              case ImageAdded(image, source) =>
                import spray.httpx.SprayJsonSupport._
                complete((Created, image))
            }
          }
        }
      } ~ pathPrefix(LongNumber) { imageId =>
        pathEndOrSingleSlash {
          get {
            onSuccess(storageService.ask(GetImagePath(imageId)).mapTo[Option[ImagePath]]) {
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
            onSuccess(storageService.ask(DeleteImage(imageId))) {
              case ImageDeleted(imageId) =>
                complete(NoContent)
            }
          }
        } ~ path("attributes") {
          get {
            onSuccess(storageService.ask(GetImageAttributes(imageId)).mapTo[Option[List[ImageAttribute]]]) {
              import spray.httpx.SprayJsonSupport._
              complete(_)
            }
          }
        } ~ path("imageinformation") {
          get {
            onSuccess(storageService.ask(GetImageInformation(imageId)).mapTo[Option[ImageInformation]]) {
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
                onSuccess(storageService.ask(GetImageFrame(imageId, frameNumber, min, max, height)).mapTo[Option[Array[Byte]]]) {
                  _ match {
                    case Some(bytes) => complete(HttpEntity(`image/png`, HttpData(bytes)))
                    case None        => complete(NotFound)
                  }
                }
              }
            }
        }
      } ~ path("delete") {
        post {
          import spray.httpx.SprayJsonSupport._
          entity(as[Seq[Long]]) { imageIds =>
            val futureDeleted = Future.sequence {
              imageIds.map(imageId => storageService.ask(DeleteImage(imageId)))
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
            else
              complete(storageService.ask(CreateTempZipFile(imageIds)).mapTo[FileName])
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
              onSuccess(storageService.ask(AddJpeg(jpegBytes, studyId, source)).mapTo[Option[Image]]) {
                _ match {
                  case Some(image) =>
                    import spray.httpx.SprayJsonSupport._
                    complete((Created, image))
                  case _ =>
                    complete(NotFound)
                }
              }
            }
          }
        }
      }
    }

}
