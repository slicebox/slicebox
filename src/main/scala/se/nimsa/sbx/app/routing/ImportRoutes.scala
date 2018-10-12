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
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileInfo
import akka.pattern.ask
import akka.stream.scaladsl.{Source => StreamSource}
import akka.util.ByteString
import se.nimsa.dicom.streams.DicomStreamException
import se.nimsa.sbx.app.GeneralProtocol.SourceType.IMPORT
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.dicom.Contexts
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.importing.ImportProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.GetImage
import se.nimsa.sbx.user.UserProtocol.ApiUser

import scala.concurrent.Future
import scala.util.{Failure, Success}

trait ImportRoutes {
  this: SliceboxBase =>

  def importRoutes(apiUser: ApiUser): Route =
    path("import" / "sessions" / LongNumber / "images") { id =>
      post {
        withoutSizeLimit {
          fileUpload("file") {
            case (fileInfo, bytes) => addImageToImportSessionRoute(Some(fileInfo), bytes, id)
          } ~ extractDataBytes { bytes =>
            addImageToImportSessionRoute(None, bytes, id)
          }
        }
      }
    } ~ pathPrefix("import") {

      pathPrefix("sessions") {
        pathEndOrSingleSlash {
          get {
            parameters((
              'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
              'count.as(nonNegativeFromStringUnmarshaller) ? 20)) { (startIndex, count) =>
              onSuccess(importService.ask(GetImportSessions(startIndex, count))) {
                case ImportSessions(importSessions) =>
                  complete(importSessions)
              }
            }
          } ~ post {
            entity(as[ImportSession]) { importSession =>
              onSuccess(importService.ask(AddImportSession(importSession.copy(user = apiUser.user, userId = apiUser.id)))) {
                case importSession: ImportSession =>
                  complete((Created, importSession))
              }
            }
          }
        } ~ pathPrefix(LongNumber) { id =>
          pathEndOrSingleSlash {
            (get & rejectEmptyResponse) {
              complete(importService.ask(GetImportSession(id)).mapTo[Option[ImportSession]])
            } ~ delete {
              complete(importService.ask(DeleteImportSession(id)).map(_ => {
                system.eventStream.publish(SourceDeleted(SourceRef(IMPORT, id)))
                NoContent
              }))
            }
          } ~ path("images") {
            get {
              onSuccess(importService.ask(GetImportSessionImages(id))) {
                case ImportSessionImages(importSessionImages) =>
                  complete {
                    Future.sequence {
                      importSessionImages.map { importSessionImage =>
                        metaDataService.ask(GetImage(importSessionImage.imageId)).mapTo[Option[Image]]
                      }
                    }.map(_.flatten)
                  }
              }
            }
          }
        }
      }

    }

  def addImageToImportSessionRoute(fileInfo: Option[FileInfo], bytes: StreamSource[ByteString, Any], importSessionId: Long): Route = {

    onSuccess(importService.ask(GetImportSession(importSessionId)).mapTo[Option[ImportSession]]) {
      case Some(importSession) =>

        val source = Source(SourceType.IMPORT, importSession.name, importSessionId)
        val futureImport = storeDicomData(bytes, source, storage, Contexts.imageDataContexts, reverseAnonymization = true)

        onComplete(futureImport) {
          case Success(metaData) =>
            onSuccess(importService.ask(AddImageToSession(importSession.id, metaData.image, !metaData.imageAdded)).mapTo[ImageAddedToSession]) { _ =>
              val overwrite = !metaData.imageAdded
              system.eventStream.publish(ImageAdded(metaData.image.id, source, overwrite))
              val httpStatus = if (metaData.imageAdded) Created else OK
              complete((httpStatus, metaData.image))
            }
          case Failure(failure) =>
            val status = failure match {
              case _: DicomStreamException => BadRequest
              case _ => InternalServerError
            }
            fileInfo match {
              case Some(fi) =>
                SbxLog.error("Import", s"${failure.getClass.getSimpleName} during import of ${fi.fileName}: ${failure.getMessage}")
                onComplete(importService.ask(UpdateSessionWithRejection(importSession.id))) {
                  _ => complete((status, s"${fi.fileName}: ${failure.getMessage}"))
                }
              case None =>
                SbxLog.error("Import", s"${failure.getClass.getSimpleName} during import: ${failure.getMessage}")
                onComplete(importService.ask(UpdateSessionWithRejection(importSession.id))) {
                  _ => complete((status, failure.getMessage))
                }
            }
        }
      case None =>
        complete(NotFound)
    }
  }
}
