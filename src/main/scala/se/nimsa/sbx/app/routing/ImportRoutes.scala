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


import scala.concurrent.Future
import akka.pattern.ask
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.anonymization.AnonymizationProtocol.ReverseAnonymization
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxServices
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.user.UserProtocol.ApiUser
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.StreamConverters
import akka.stream.scaladsl.{Source => StreamSource}
import akka.util.ByteString
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, GetImage, MetaDataAdded}
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.importing.ImportProtocol._

import scala.util.{Failure, Success}

trait ImportRoutes {
  this: SliceboxServices =>

  def importRoutes(apiUser: ApiUser): Route =
    path("import" / "sessions" / LongNumber / "images") { id =>
      post {
        fileUpload("file") {
          case (_, bytes) => addImageToImportSessionRoute(bytes, id)
        } ~ extractDataBytes { bytes =>
          addImageToImportSessionRoute(bytes, id)
        }
      }
    } ~ pathPrefix("import") {

      pathPrefix("sessions") {
        pathEndOrSingleSlash {
          get {
            parameters(
              'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
              'count.as(nonNegativeFromStringUnmarshaller) ? 20) { (startIndex, count) =>
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
            get {
              complete(importService.ask(GetImportSession(id)).mapTo[Option[ImportSession]])
            } ~ delete {
              complete(importService.ask(DeleteImportSession(id)).map(_ =>
                NoContent))
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

  def addImageToImportSessionRoute(bytes: StreamSource[ByteString, Any], importSessionId: Long): Route = {
    val is = bytes.runWith(StreamConverters.asInputStream())
    val dicomData = DicomUtil.loadDicomData(is, withPixelData = true)
    onSuccess(importService.ask(GetImportSession(importSessionId)).mapTo[Option[ImportSession]]) {
      case Some(importSession) =>

        val source = Source(SourceType.IMPORT, importSession.name, importSessionId)

        onComplete(storageService.ask(CheckDicomData(dicomData, useExtendedContexts = false)).mapTo[Boolean]) {

          case Success(status) =>
            onSuccess(anonymizationService.ask(ReverseAnonymization(dicomData.attributes)).mapTo[Attributes]) { reversedAttributes =>
              onSuccess(metaDataService.ask(AddMetaData(reversedAttributes, source)).mapTo[MetaDataAdded]) { metaData =>
                onSuccess(storageService.ask(AddDicomData(dicomData.copy(attributes = reversedAttributes), source, metaData.image)).mapTo[DicomDataAdded]) { dicomDataAdded =>
                  onSuccess(importService.ask(AddImageToSession(importSession.id, dicomDataAdded.image, dicomDataAdded.overwrite)).mapTo[ImageAddedToSession]) { importSessionImage =>
                    if (dicomDataAdded.overwrite)
                      complete((OK, dicomDataAdded.image))
                    else
                      complete((Created, dicomDataAdded.image))
                  }
                }
              }
            }

          case Failure(e) =>
            importService.ask(UpdateSessionWithRejection(importSession))
            complete((BadRequest, e.getMessage))

        }

      case None =>
        complete(NotFound)
    }
  }

}
