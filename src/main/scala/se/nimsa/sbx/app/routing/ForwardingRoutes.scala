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
import spray.http.ContentTypes
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing._
import se.nimsa.sbx.user.AuthInfo
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.user.UserProtocol.UserRole
import se.nimsa.sbx.forwarding.ForwardingProtocol._

trait ForwardingRoutes { this: RestApi =>

  def forwardingRoutes(authInfo: AuthInfo): Route =
    pathPrefix("forwarding") {
      pathPrefix("rules") {
        pathEndOrSingleSlash {
          get {
            onSuccess(forwardingService.ask(GetForwardingRules)) {
              case ForwardingRules(forwardingRules) =>
                complete(forwardingRules)
            }
          } ~ post {
            authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
              entity(as[ForwardingRule]) { forwardingRule =>
                onSuccess(forwardingService.ask(AddForwardingRule(forwardingRule))) {
                  case ForwardingRuleAdded(forwardingRule) =>
                    complete((Created, forwardingRule))
                }
              }
            }
          }
        } ~ pathPrefix(LongNumber) { forwardingRuleId =>
          pathEndOrSingleSlash {
            delete {
              authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
                onSuccess(forwardingService.ask(RemoveForwardingRule(forwardingRuleId))) {
                  case ForwardingRuleRemoved(forwardingRuleId) =>
                    complete(NoContent)
                }
              }
            }
          }
        }
      }
    }

}
