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


import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.metadata.MetaDataProtocol._
import akka.http.scaladsl.model.StatusCodes.{BadRequest, Created, InternalServerError, NoContent, NotFound}

import scala.util.{Failure, Success}


trait SeriesTagsRoutes {
  this: SliceboxBase =>

  def seriesTagsRoutes: Route =
    pathPrefix("metadata") {
      pathPrefix("seriestags") {
        pathEndOrSingleSlash {
          get {
            onSuccess(metaDataService.ask(GetSeriesTags)) {
              case SeriesTags(seriesTags) =>
                complete(seriesTags)
            }

          } ~ post {
            entity(as[SeriesTag]) { seriesTag =>
              onComplete(metaDataService.ask(CreateSeriesTag(seriesTag))) {
                case Success(tag: SeriesTag) => complete((Created, tag))
                case Failure(ex) => {
                  val msg = ex.getMessage
                  if (msg.contains("idx_unique_series_tag_name")) {
                    complete((BadRequest, msg))
                  } else {
                    complete((InternalServerError, msg))
                  }
                }

              }
            }
          }
        } ~ path(LongNumber) { tagId =>
          pathEndOrSingleSlash {
            get {
              onSuccess(metaDataService.ask(GetSeriesTag(tagId))) {
                case Some(tag: SeriesTag) =>
                  complete(tag)
              }
            } ~ put {
              entity(as[SeriesTag]) { seriesTag =>
                onSuccess(metaDataService.ask(UpdateSeriesTag(seriesTag))) {
                  case Some(tag: SeriesTag) =>
                    complete(tag)
                  case None =>
                    complete(NotFound)
                }
              }
            } ~ delete {
             onSuccess(metaDataService.ask(DeleteSeriesTag(tagId))) {
                case SeriesTags(seriesTags) =>
                  complete(NoContent)
              }
            }
          }
        }
      }
    }
}