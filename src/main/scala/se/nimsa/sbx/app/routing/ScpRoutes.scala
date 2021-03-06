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
import akka.pattern.ask
import se.nimsa.sbx.app.GeneralProtocol.SourceType.SCP
import se.nimsa.sbx.app.GeneralProtocol.{SourceAdded, SourceDeleted, SourceRef}
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.scp.ScpProtocol._
import se.nimsa.sbx.user.UserProtocol._

trait ScpRoutes { this: SliceboxBase =>

def scpRoutes(apiUser: ApiUser): Route =
    pathPrefix("scps") {
      pathEndOrSingleSlash {
        get {
          parameters((
            'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
            'count.as(nonNegativeFromStringUnmarshaller) ? 20)) { (startIndex, count) =>
            onSuccess(scpService.ask(GetScps(startIndex, count))) {
              case Scps(scps) =>
                complete(scps)
            }
          }
        } ~ post {
          authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
            entity(as[ScpData]) { scp =>
              onSuccess(scpService.ask(AddScp(scp))) {
                case scpData: ScpData => {
                  system.eventStream.publish(SourceAdded(SourceRef(SCP, scpData.id)))
                  complete((Created, scpData))
                }
              }
            }
          }
        }
      } ~ path(LongNumber) { scpDataId =>
        delete {
          authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
            onSuccess(scpService.ask(RemoveScp(scpDataId))) {
              case ScpRemoved(_) => {
                system.eventStream.publish(SourceDeleted(SourceRef(SCP, scpDataId)))
                complete(NoContent)
              }
            }
          }
        }
      }
    }

}
