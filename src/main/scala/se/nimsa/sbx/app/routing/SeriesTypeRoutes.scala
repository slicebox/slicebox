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
import spray.http.ContentTypes
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing._
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.metadata.MetaDataProtocol._

trait SeriesTypeRoutes { this: SliceboxService =>

  def seriesTypeRoutes(apiUser: ApiUser): Route =
    pathPrefix("seriestypes") {
      pathEndOrSingleSlash {
        get {
          parameters('startindex.as[Long] ? 0, 'count.as[Long] ? 20) { (startIndex, count) =>
            onSuccess(seriesTypeService.ask(GetSeriesTypes(startIndex, count))) {
              case SeriesTypes(seriesTypes) =>
                complete(seriesTypes)
            }
          }
        } ~ post {
          authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
            entity(as[SeriesType]) { seriesType =>
              onSuccess(seriesTypeService.ask(AddSeriesType(seriesType))) {
                case SeriesTypeAdded(seriesType) =>
                  complete((Created, seriesType))
              }
            }
          }
        }

      } ~ pathPrefix(LongNumber) { seriesTypeId =>
        pathEndOrSingleSlash {
          put {
            authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
              entity(as[SeriesType]) { seriesType =>
                onSuccess(seriesTypeService.ask(UpdateSeriesType(seriesType))) {
                  case SeriesTypeUpdated =>
                    complete(NoContent)
                }
              }
            }
          } ~ delete {
            authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
              onSuccess(seriesTypeService.ask(RemoveSeriesType(seriesTypeId))) {
                case SeriesTypeRemoved(seriesTypeId) =>
                  complete(NoContent)
              }
            }
          }
        }
      } ~ pathPrefix("rules") {
        path("updatestatus") {
          onSuccess(seriesTypeService.ask(GetUpdateSeriesTypesRunningStatus)) {
            case UpdateSeriesTypesRunningStatus(running) =>
              if (running)
                complete("running")
              else
                complete("idle")
          }
        } ~ pathEndOrSingleSlash {
          get {
            parameter('seriestypeid.as[Long]) { seriesTypeId =>
              onSuccess(seriesTypeService.ask(GetSeriesTypeRules(seriesTypeId))) {
                case SeriesTypeRules(seriesTypeRules) =>
                  complete(seriesTypeRules)
              }
            }
          } ~ post {
            authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
              entity(as[SeriesTypeRule]) { seriesTypeRule =>
                onSuccess(seriesTypeService.ask(AddSeriesTypeRule(seriesTypeRule))) {
                  case SeriesTypeRuleAdded(seriesTypeRule) =>
                    complete((Created, seriesTypeRule))
                }
              }
            }
          }
        } ~ pathPrefix(LongNumber) { seriesTypeRuleId =>
          pathEndOrSingleSlash {
            delete {
              authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
                onSuccess(seriesTypeService.ask(RemoveSeriesTypeRule(seriesTypeRuleId))) {
                  case SeriesTypeRuleRemoved(seriesTypeRuleId) =>
                    complete(NoContent)
                }
              }
            }
          } ~ pathPrefix("attributes") {
            pathEndOrSingleSlash {
              get {
                onSuccess(seriesTypeService.ask(GetSeriesTypeRuleAttributes(seriesTypeRuleId))) {
                  case SeriesTypeRuleAttributes(seriesTypeRuleAttributes) =>
                    complete(seriesTypeRuleAttributes)
                }
              } ~ post {
                authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
                  entity(as[SeriesTypeRuleAttribute]) { seriesTypeRuleAttribute =>
                    onSuccess(seriesTypeService.ask(AddSeriesTypeRuleAttribute(seriesTypeRuleAttribute))) {
                      case SeriesTypeRuleAttributeAdded(seriesTypeRuleAttribute) =>
                        complete((Created, seriesTypeRuleAttribute))
                    }
                  }
                }
              }
            } ~ pathPrefix(LongNumber) { seriesTypeRuleAttributeId =>
              pathEndOrSingleSlash {
                delete {
                  authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
                    onSuccess(seriesTypeService.ask(RemoveSeriesTypeRuleAttribute(seriesTypeRuleAttributeId))) {
                      case SeriesTypeRuleAttributeRemoved(seriesTypeRuleAttributeId) =>
                        complete(NoContent)
                    }
                  }
                }
              }
            }
          }
        }

      }
    }
}
