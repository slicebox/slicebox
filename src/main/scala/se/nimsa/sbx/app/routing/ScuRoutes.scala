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

import akka.pattern.ask

import spray.http.StatusCodes.NoContent
import spray.http.StatusCodes.Created
import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.nimsa.sbx.app.AuthInfo
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.app.UserProtocol.UserRole
import se.nimsa.sbx.dicom.DicomProtocol._

trait ScuRoutes { this: RestApi =>

  def scuRoutes(authInfo: AuthInfo): Route =
    pathPrefix("scus") {
      pathEndOrSingleSlash {
        get {
          onSuccess(dicomService.ask(GetScus)) {
            case Scus(scus) =>
              complete(scus)
          }
        } ~ post {
          authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
            entity(as[AddScu]) { addScu =>
              onSuccess(dicomService.ask(addScu)) {
                case scuData: ScuData =>
                  complete((Created, scuData))
              }
            }
          }
        }
      } ~ pathPrefix(LongNumber) { scuDataId =>
        pathEndOrSingleSlash {
          delete {
            authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
              onSuccess(dicomService.ask(RemoveScu(scuDataId))) {
                case ScuRemoved(scuDataId) =>
                  complete(NoContent)
              }
            }
          }
        } ~ path("sendseries" / LongNumber) { seriesId =>
          post {
            onSuccess(dicomService.ask(SendSeriesToScp(seriesId, scuDataId))) {
              case ImagesSentToScp(scuDataId, imageIds) => complete(NoContent)
            }
          }
        }
      }
    }

}
