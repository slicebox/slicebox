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

trait ImportRoutes { this: SliceboxService =>

  def importRoutes(apiUser: ApiUser): Route =
    pathPrefix("importing") {
      pathPrefix("sessions") {
        pathEndOrSingleSlash {
          get {
            complete(OK)
          } ~ post {
            complete(Created)
          }
        } ~ path(LongNumber) { id =>
          get {
            complete(OK)
          } ~ delete {
            complete(NoContent)
          }
        }
      }
    }
}
