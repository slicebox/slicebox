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
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.user.UserProtocol.ApiUser
import spray.http.FormFile
import spray.http.StatusCodes._
import spray.routing.Route
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, GetImage, MetaDataAdded}
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.importing.ImportProtocol._

import scala.util.{Failure, Success}

trait ImportRoutes {
  this: SliceboxService =>

  def importRoutes(apiUser: ApiUser): Route =
    path("import" / "sessions" / LongNumber / "images") { id =>
      post {
        formField('file.as[FormFile]) { file =>
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
            parameters('startindex.as[Long] ? 0, 'count.as[Long] ? 20) { (startIndex, count) =>
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
              onSuccess(importService.ask(DeleteImportSession(id))) {
                case _ =>
                  complete(NoContent)
              }
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

  def addImageToImportSessionRoute(bytes: Array[Byte], importSessionId: Long): Route = {
    import spray.httpx.SprayJsonSupport._

    val dataset = DicomUtil.loadDataset(bytes, withPixelData = true, useBulkDataURI = false)
    onSuccess(importService.ask(GetImportSession(importSessionId)).mapTo[Option[ImportSession]]) {
      case Some(importSession) =>

        val source = Source(SourceType.IMPORT, importSession.name, importSessionId)

        onComplete(storageService.ask(CheckDataset(dataset, restrictSopClass = true)).mapTo[Boolean]) {

          case Success(status) =>
            onSuccess(anonymizationService.ask(ReverseAnonymization(dataset)).mapTo[Attributes]) { reversedDataset =>
              onSuccess(metaDataService.ask(AddMetaData(reversedDataset, source)).mapTo[MetaDataAdded]) { metaData =>
                onSuccess(storageService.ask(AddDataset(reversedDataset, source, metaData.image)).mapTo[DatasetAdded]) { datasetAdded =>
                  onSuccess(importService.ask(AddImageToSession(importSession.id, datasetAdded.image, datasetAdded.overwrite)).mapTo[ImageAddedToSession]) { importSessionImage =>
                    if (datasetAdded.overwrite)
                      complete((OK, datasetAdded.image))
                    else
                      complete((Created, datasetAdded.image))
                  }
                }
              }
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
