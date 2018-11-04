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
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.filtering.FilteringProtocol._
import se.nimsa.sbx.user.UserProtocol._

trait FilteringRoutes {
  this: SliceboxBase =>

  def filteringRoutes(apiUser: ApiUser): Route =
    pathPrefix("filtering") {
      pathPrefix("filters") {
        pathEndOrSingleSlash {
          get {
            parameters((
              'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
              'count.as(nonNegativeFromStringUnmarshaller) ? 20)) { (startIndex, count) =>
              onSuccess(filteringService.ask(GetTagFilters(startIndex, count))) {
                case TagFilters(filters) =>
                  complete(filters)
              }
            }
          } ~ post {
            authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
              entity(as[TagFilter]) { filter =>
                onSuccess(filteringService.ask(AddTagFilter(filter))) {
                  case TagFilterAdded(addedFilter) =>
                    complete((Created, addedFilter))
                }
              }
            }
          }
        } ~ pathPrefix(LongNumber) { tagFilterId =>
          pathEndOrSingleSlash {
            delete {
              authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
                onSuccess(filteringService.ask(RemoveTagFilter(tagFilterId))) {
                  case TagFilterRemoved(_) =>
                    complete(NoContent)
                }
              }
            }
          } ~ pathPrefix("tagpaths") {
            pathEndOrSingleSlash {
              get {
                parameters((
                  'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
                  'count.as(nonNegativeFromStringUnmarshaller) ? 20)) { (startIndex, count) =>
                  onSuccess(filteringService.ask(GetTagFilterTagPaths(tagFilterId, startIndex, count))) {
                    case TagFilterTagPaths(tagPaths) =>
                      complete(tagPaths)
                  }
                }
              } ~ post {
                authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
                  entity(as[TagFilterTagPath]) { tagFilterTagPath =>
                    onSuccess(filteringService.ask(AddTagFilterTagPath(tagFilterTagPath))) {
                      case TagFilterTagPathAdded(addedTagFilterTagPath) =>
                        complete((Created, addedTagFilterTagPath))
                    }
                  }
                }
              }
            } ~ pathPrefix(LongNumber) { tagFilterTagPathId =>
              delete {
                authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
                  onSuccess(filteringService.ask(RemoveTagFilterTagPath(tagFilterTagPathId))) {
                    case TagFilterTagPathRemoved(_) =>
                      complete(NoContent)
                  }
                }
              }
            }
          }
        }
      } ~ pathPrefix("associations") {
        pathEndOrSingleSlash {
          get {
            parameters((
              'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
              'count.as(nonNegativeFromStringUnmarshaller) ? 20)) { (startIndex, count) =>
              onSuccess(filteringService.ask(GetSourceTagFilters(startIndex, count))) {
                case SourceTagFilters(sourceTagFilters) =>
                  complete(sourceTagFilters)
              }
            }
          } ~ post {
            authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
              entity(as[SourceTagFilter]) { sourceFilterAssociation =>
                onSuccess(filteringService.ask(AddSourceTagFilter(sourceFilterAssociation))) {
                  case SourceTagFilterAdded(sourceTagFilter) =>
                    complete((Created, sourceTagFilter))
                }
              }
            }
          }
        } ~ pathPrefix(LongNumber) { sourceTagFilterId =>
          pathEndOrSingleSlash {
            delete {
              authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
                onSuccess(filteringService.ask(RemoveSourceTagFilter(sourceTagFilterId))) {
                  case _: SourceTagFilterRemoved =>
                    complete(NoContent)
                }
              }
            }
          }
        }
      }
    }
}
