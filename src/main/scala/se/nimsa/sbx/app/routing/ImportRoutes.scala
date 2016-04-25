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
import se.nimsa.sbx.metadata.MetaDataProtocol.Images
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.importing.ImportProtocol._
import akka.util.ByteString
import scala.util.{Try, Success, Failure}

trait ImportRoutes { this: SliceboxService =>

  def importRoutes(apiUser: ApiUser): Route =
    pathPrefix("import" / "sessions" / LongNumber / "images") { id =>
      post {
        formField('file.as[FormFile]) { file =>
          file.
          addImageToImportSessionRoute(file.entity.data.toByteArray, id)
        } ~ entity(as[Array[Byte]]) { bytes =>
          addImageToImportSessionRoute(bytes, id)
        }
      }
    } ~ pathPrefix("import") {
      import spray.httpx.SprayJsonSupport._
      
      pathPrefix("sessions") {
        pathEndOrSingleSlash {
          get {
            onSuccess(importService.ask(GetImportSessions())){
              case ImportSessions(importSessions) => {
                complete(importSessions)
              }
            }
          } ~ post {
            entity(as[ImportSession]) { importSession =>
              onSuccess(importService.ask(AddImportSession(importSession.copy(user = apiUser.user, userId = apiUser.id)))) {
                case is: ImportSession => {
                  complete((Created, is))
                }
              }

            }
            //complete((Created, ImportSession(12, "my import", 34, "user", 0, 0, System.currentTimeMillis, System.currentTimeMillis)))
          }
        } ~ pathPrefix(LongNumber) { id =>
          pathEndOrSingleSlash {
            get {
              onSuccess(importService.ask(GetImportSession(id)).mapTo[Option[ImportSession]]) {
                case Some(importSession) =>
                  complete(importSession)
                case _ =>
                  complete(NotFound)
              }
            } ~ delete {
              onSuccess(importService.ask(DeleteImportSession(id))) {
                case _ =>
                  complete(NoContent)
              }
            }
          } ~ path("images") {
            get {
              onSuccess(importService.ask(GetImportSessionImages(id))) {
                case ImportSessionImages(importSessionImages) =>
                  complete(importSessionImages.map(_.imageId))
              }
            }
          }
        }
      }

    }

  def addImageToImportSessionRoute(bytes: Array[Byte], importSessionId: Long): Route = {
    import spray.httpx.SprayJsonSupport._

    val dataset = DicomUtil.loadDataset(bytes, true)
    onSuccess(importService.ask(GetImportSession(importSessionId)).mapTo[Option[ImportSession]]) {
      case Some(importSession) =>
        val source = Source(SourceType.IMPORT, importSession.name, importSessionId)
        onComplete(storageService.ask(AddDataset(dataset, source))) {

          case Success(DatasetAdded(image, source, overwrite)) =>
            onSuccess(importService.ask(AddImageToSession(importSession, image, overwrite))) {

              case Some(ImageAddedToSession(importSessionImage)) =>
                if (overwrite)
                  complete((OK, image))
                else
                  complete((Created, image))

              case None =>
                complete(NotFound)
            }

          case Failure(e) =>
            importService.ask(UpdateSessionWithRejection(importSession))
            complete(BadRequest)
        }
      case None =>
        complete(NotFound)
    }
  }

}
