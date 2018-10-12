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

import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import se.nimsa.sbx.app.GeneralProtocol.SourceRef
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.filtering.FilteringProtocol._
import se.nimsa.sbx.forwarding.ForwardingProtocol._
import se.nimsa.sbx.user.UserProtocol._

trait FilteringRoutes {
  this: SliceboxBase =>

  def filteringRoutes(apiUser: ApiUser): Route =
    pathPrefix("filtering") {
      pathPrefix("tagfilter") {
        pathEndOrSingleSlash {
          get {
            parameters((
              'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
              'count.as(nonNegativeFromStringUnmarshaller) ? 20)) { (startIndex, count) =>
              onSuccess(filteringService.ask(GetTagFilters(startIndex, count))) {
                case TagFilterSpecs(tagFilterSpecs) =>
                  complete(tagFilterSpecs)
              }
            }
          } ~ post {
            authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
              entity(as[TagFilterSpec]) { filterSpecification =>
                onSuccess(filteringService.ask(AddTagFilter(filterSpecification))) {
                  case TagFilterAdded(addedTagFilter) =>
                    complete((Created, addedTagFilter))
                }
              }
            }
          }
        } ~ pathPrefix(LongNumber) { tagFilterId =>
          pathEndOrSingleSlash {
            get {
              authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
                onSuccess(filteringService.ask(GetTagFilter(tagFilterId)).mapTo[Option[TagFilterSpec]]) {
                  case Some(tfs) =>
                    complete((OK, tfs))
                  case None =>
                    complete(NotFound)
                }
              }
            } ~ delete {
              authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
                onSuccess(filteringService.ask(RemoveTagFilter(tagFilterId))) {
                  case TagFilterRemoved(_) =>
                    complete(NoContent)
                }
              }
            } ~ post {
              authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
                entity(as[SourceRef]) { sRef =>
                  onSuccess(filteringService.ask(SetFilterForSource(sRef, tagFilterId))) {
                    case sourceTagFilter: SourceTagFilter =>
                      complete((Created, sourceTagFilter))
                  }
                }
              }
            }
          }
        }
      }
    }
}
