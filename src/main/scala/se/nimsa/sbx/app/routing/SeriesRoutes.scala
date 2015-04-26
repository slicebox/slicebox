/*
 * Copyright 2015 Karl Sjöstrand
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

import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.dicom.DicomProtocol._

trait SeriesRoutes { this: RestApi =>

  def seriesRoutes: Route =
    pathPrefix("series") {
      path("datasets") {
        get {
          parameters('seriesid.as[Long]) { seriesId =>
            onSuccess(dicomService.ask(GetImages(seriesId))) {
              case Images(images) =>
                val imageURLs =
                  images.map(image => SeriesDataset(image.id, s"$apiBaseURL/images/${image.id}"))
                complete(imageURLs)
            }
          }
        }
      }
    }

}
