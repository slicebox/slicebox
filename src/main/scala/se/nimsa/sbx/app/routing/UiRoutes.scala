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

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import se.nimsa.sbx.app.SliceboxServices

trait UiRoutes { this: SliceboxServices =>

  def staticResourcesRoute =
    get {
      path(Remaining) { remaining =>
        getFromResource("public/" + remaining)
      }
    }

  def angularRoute =
    get {
      getFromResource("public/index.html")
    }

  def faviconRoutes: Route =
    path("apple-touch-icon-57x57.png") {
      getFromResource("public/images/favicons/apple-touch-icon-57x57.png")
    } ~ path("apple-touch-icon-60x60.png") {
      getFromResource("public/images/favicons/apple-touch-icon-60x60.png")
    } ~ path("apple-touch-icon-72x72.png") {
      getFromResource("public/images/favicons/apple-touch-icon-72x72.png")
    } ~ path("apple-touch-icon-76x76.png") {
      getFromResource("public/images/favicons/apple-touch-icon-76x76.png")
    } ~ path("apple-touch-icon-114x114.png") {
      getFromResource("public/images/favicons/apple-touch-icon-114x114.png")
    } ~ path("apple-touch-icon-120x120.png") {
      getFromResource("public/images/favicons/apple-touch-icon-120x120.png")
    } ~ path("apple-touch-icon-144x144.png") {
      getFromResource("public/images/favicons/apple-touch-icon-144x144.png")
    } ~ path("apple-touch-icon-152x152.png") {
      getFromResource("public/images/favicons/apple-touch-icon-152x152.png")
    } ~ path("apple-touch-icon-180x180.png") {
      getFromResource("public/images/favicons/apple-touch-icon-180x180.png")
    } ~ path("favicon.ico") {
      getFromResource("public/images/favicons/favicon.ico")
    } ~ path("favicon-16x16.png") {
      getFromResource("public/images/favicons/favicon-16x16.png")
    } ~ path("favicon-32x32.png") {
      getFromResource("public/images/favicons/favicon-32x32.png")
    } ~ path("favicon-96x96.png") {
      getFromResource("public/images/favicons/favicon-96x96.png")
    } ~ path("favicon-194x194.png") {
      getFromResource("public/images/favicons/favicon-194x194.png")
    } ~ path("android-chrome-36x36.png") {
      getFromResource("public/images/favicons/android-chrome-36x36.png")
    } ~ path("android-chrome-48x48.png") {
      getFromResource("public/images/favicons/android-chrome-48x48.png")
    } ~ path("android-chrome-72x72.png") {
      getFromResource("public/images/favicons/android-chrome-72x72.png")
    } ~ path("android-chrome-96x96.png") {
      getFromResource("public/images/favicons/android-chrome-96x96.png")
    } ~ path("android-chrome-144x144.png") {
      getFromResource("public/images/favicons/android-chrome-144x144.png")
    } ~ path("android-chrome-192x192.png") {
      getFromResource("public/images/favicons/android-chrome-192x192.png")
    } ~ path("mstile-70x70.png") {
      getFromResource("public/images/favicons/mstile-70x70.png")
    } ~ path("mstile-144x144.png") {
      getFromResource("public/images/favicons/mstile-144x144.png")
    } ~ path("mstile-150x150.png") {
      getFromResource("public/images/favicons/mstile-150x150.png")
    } ~ path("mstile-310x150.png") {
      getFromResource("public/images/favicons/mstile-310x150.png")
    } ~ path("mstile-310x310.png") {
      getFromResource("public/images/favicons/mstile-310x310.png")
    } ~ path("manifest.json") {
      getFromResource("public/images/favicons/manifest.json")
    } ~ path("browserconfig.xml") {
      getFromResource("public/images/favicons/browserconfig.xml")
    }

}
