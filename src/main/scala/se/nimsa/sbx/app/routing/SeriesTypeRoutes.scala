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

import akka.pattern.ask
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import se.nimsa.sbx.user.UserProtocol._

trait SeriesTypeRoutes {
  this: SliceboxBase =>

  def seriesTypeRoutes(apiUser: ApiUser): Route =
    pathPrefix("seriestypes") {
      pathEndOrSingleSlash {
        get {
          parameters((
            'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
            'count.as(nonNegativeFromStringUnmarshaller) ? 20)) { (startIndex, count) =>
            onSuccess(seriesTypeService.ask(GetSeriesTypes(startIndex, count))) {
              case SeriesTypes(seriesTypes) =>
                complete(seriesTypes)
            }
          }
        } ~ post {
          authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
            entity(as[SeriesType]) { seriesType =>
              onSuccess(seriesTypeService.ask(AddSeriesType(seriesType))) {
                case SeriesTypeAdded(addedSeriesType) =>
                  complete((Created, addedSeriesType))
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
                case SeriesTypeRemoved(_) =>
                  complete(NoContent)
              }
            }
          }
        }
      } ~ pathPrefix("rules") {
        path("updatestatus") {
          onSuccess(seriesTypeService.ask(GetUpdateSeriesTypesRunningStatus).mapTo[UpdateSeriesTypesRunningStatus]) { status =>
            complete(status)
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
                  case SeriesTypeRuleAdded(addedSeriesTypeRule) =>
                    complete((Created, addedSeriesTypeRule))
                }
              }
            }
          }
        } ~ pathPrefix(LongNumber) { seriesTypeRuleId =>
          pathEndOrSingleSlash {
            delete {
              authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
                onSuccess(seriesTypeService.ask(RemoveSeriesTypeRule(seriesTypeRuleId))) {
                  case SeriesTypeRuleRemoved(_) =>
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
                      case SeriesTypeRuleAttributeAdded(addedSeriesTypeRuleAttribute) =>
                        complete((Created, addedSeriesTypeRuleAttribute))
                    }
                  }
                }
              }
            } ~ pathPrefix(LongNumber) { seriesTypeRuleAttributeId =>
              pathEndOrSingleSlash {
                delete {
                  authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
                    onSuccess(seriesTypeService.ask(RemoveSeriesTypeRuleAttribute(seriesTypeRuleAttributeId))) {
                      case SeriesTypeRuleAttributeRemoved(_) =>
                        complete(NoContent)
                    }
                  }
                }
              }
            }
          }
        }
      } ~ pathPrefix("series") {
        path("query") {
          post {
            entity(as[IdsQuery]) { query =>
              onSuccess(seriesTypeService.ask(GetSeriesTypesForListOfSeries(query))) {
                case result: SeriesIdSeriesTypesResult =>
                  complete(result)
              }
            }
          }
        }
      }
    }
}