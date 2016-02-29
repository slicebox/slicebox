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

import akka.pattern.ask

import spray.http.StatusCodes.NoContent
import spray.http.StatusCodes.Created
import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.scp.ScpProtocol._

trait ScpRoutes { this: SliceboxService =>

def scpRoutes(apiUser: ApiUser): Route =
    pathPrefix("scps") {
      pathEndOrSingleSlash {
        get {
          onSuccess(scpService.ask(GetScps)) {
            case Scps(scps) =>
              complete(scps)
          }
        } ~ post {
          authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
            entity(as[ScpData]) { scp =>
              onSuccess(scpService.ask(AddScp(scp))) {
                case scpData: ScpData =>
                  complete((Created, scpData))
              }
            }
          }
        }
      } ~ path(LongNumber) { scpDataId =>
        delete {
          authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
            onSuccess(scpService.ask(RemoveScp(scpDataId))) {
              case ScpRemoved(scpDataId) =>
                complete(NoContent)
            }
          }
        }
      }
    }

}
