/*
 * Copyright 2015 Karl Sjöstrand
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
import spray.routing._

import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.dicom.DicomProtocol._
import se.nimsa.sbx.dicom.DicomUtil

trait ImageRoutes { this: RestApi =>

  def imageRoutes: Route =
    pathPrefix("images") {
      pathEndOrSingleSlash {
        post {
          formField('file.as[FormFile]) { file =>
            val dataset = DicomUtil.loadDataset(file.entity.data.toByteArray, true)
            onSuccess(dicomService.ask(AddDataset(dataset))) {
              case ImageAdded(image) =>
                import spray.httpx.SprayJsonSupport._
                complete((Created, image))
            }
          } ~ entity(as[Array[Byte]]) { bytes =>
            val dataset = DicomUtil.loadDataset(bytes, true)
            onSuccess(dicomService.ask(AddDataset(dataset))) {
              case ImageAdded(image) =>
                import spray.httpx.SprayJsonSupport._
                complete((Created, image))
            }
          }
        }
      } ~ path(LongNumber) { imageId =>
        get {
          onSuccess(dicomService.ask(GetImageFile(imageId)).mapTo[Option[ImageFile]]) {
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
        }
      } ~ path(LongNumber / "attributes") { imageId =>
        get {
          onSuccess(dicomService.ask(GetImageAttributes(imageId))) {
            case ImageAttributes(attributes) =>
              import spray.httpx.SprayJsonSupport._
              complete(attributes)
          }
        }
      } ~ path(LongNumber / "imageinformation") { imageId =>
        get {
          onSuccess(dicomService.ask(GetImageInformation(imageId))) {
            case info: ImageInformation =>
              import spray.httpx.SprayJsonSupport._
              complete(info)
          }
        }
      } ~ path(LongNumber / "png") { imageId =>
        parameters(
            'framenumber.as[Int] ? 1, 
            'windowmin.as[Int] ? 0, 
            'windowmax.as[Int] ? 0, 
            'imageheight.as[Int] ? 0) { (frameNumber, min, max, height) =>
          get {
            onSuccess(dicomService.ask(GetImageFrame(imageId, frameNumber, min, max, height))) {
              case ImageFrame(bytes) =>
                complete(HttpEntity(MediaTypes.`image/png`, HttpData(bytes)))
            }
          }
        }
      }
    }

}
