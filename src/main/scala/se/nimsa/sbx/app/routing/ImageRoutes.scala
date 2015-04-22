package se.nimsa.sbx.app.routing

import akka.pattern.ask

import spray.http.ContentTypes
import spray.http.FormFile
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.MediaTypes
import spray.http.StatusCodes.BadRequest
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
          onSuccess(dicomService.ask(GetImageFile(imageId))) {
            case imageFile: ImageFile =>
              val file = storage.resolve(imageFile.fileName.value).toFile
              if (file.isFile && file.canRead)
                detach() {
                  complete(HttpEntity(ContentTypes.`application/octet-stream`, HttpData(file)))
                }
              else
                complete((BadRequest, "Dataset could not be read"))
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