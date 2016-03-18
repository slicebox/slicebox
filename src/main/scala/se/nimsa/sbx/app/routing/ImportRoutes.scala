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

trait ImportRoutes { this: SliceboxService =>

  def importRoutes: Route =
    pathPrefix("importing") {
      pathPrefix("sessions") {
        pathEndOrSingleSlash {
          import spray.httpx.SprayJsonSupport._
          get {
            complete(Seq(ImportSession(-1, "my import", 34, "user", 0, 0, System.currentTimeMillis, System.currentTimeMillis)))
          } ~ post {
            complete((Created, ImportSession(-1, "my import", 34, "user", 0, 0, System.currentTimeMillis, System.currentTimeMillis)))
          }
        } ~ pathPrefix(LongNumber) { id =>
          pathEndOrSingleSlash {
            import spray.httpx.SprayJsonSupport._
            get {
              complete(ImportSession(-1, "my import", 34, "user", 0, 0, System.currentTimeMillis, System.currentTimeMillis))
            } ~ delete {
              complete(NoContent)
            }
          } ~ path("images") {
            get {
              import spray.httpx.SprayJsonSupport._
              complete(Seq(Image(-1, -1, SOPInstanceUID("souid1"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))))
            } ~ post {
              formField('file.as[FormFile]) { file =>
                storeDataSetRoute(file.entity.data.toByteArray, id)
              } ~ entity(as[Array[Byte]]) { bytes =>
                storeDataSetRoute(bytes, id)
              }
            }

          }
        }
      }

    }

  def storeDataSetRoute(bytes: Array[Byte], importSessionId: Long): Route = {
    val dataset = DicomUtil.loadDataset(bytes, true)
    onSuccess(importService.ask(GetImportSession(importSessionId))) {
      case Some(ImportSession(_, name, _, _, _, _, _, _)) =>
        val source = Source(SourceType.IMPORT, name, importSessionId)
        onSuccess(storageService.ask(AddDataset(dataset, source))) {
          case DatasetAdded(image, source, overwrite) =>
            import spray.httpx.SprayJsonSupport._
            if (overwrite)
              complete((OK, image))
            else
              complete((Created, image))
        }
      case None =>
        complete(NotFound)
    }
  }

}
