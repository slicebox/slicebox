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

import spray.routing._
import spray.http.StatusCodes._
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.app.UserProtocol.UserRole
import spray.routing.ExceptionHandler
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.lang.BadGatewayException

trait SliceboxRoutes extends DirectoryRoutes
  with ScpRoutes
  with ScuRoutes
  with MetadataRoutes
  with ImageRoutes
  with BoxRoutes
  with ForwardingRoutes
  with RemoteBoxRoutes
  with UserRoutes
  with LogRoutes
  with SystemRoutes
  with UiRoutes
  with SeriesTypeRoutes { this: RestApi =>

  implicit val knownExceptionHandler =
    ExceptionHandler {
      case e: IllegalArgumentException =>
        complete((BadRequest, e.getMessage()))
      case e: NotFoundException =>
        complete((NotFound, e.getMessage()))
      case e: BadGatewayException =>
        complete((BadGateway, e.getMessage()))
    }

  def sliceboxRoutes: Route =
    pathPrefix("api") {
      parameter('authtoken.?) { authToken =>
        authenticate(authenticator.basicUserAuthenticator(authToken)) { authInfo =>
          directoryRoutes(authInfo) ~
            scpRoutes(authInfo) ~
            scuRoutes(authInfo) ~
            metaDataRoutes ~
            imageRoutes(authInfo) ~
            boxRoutes(authInfo) ~
            userRoutes(authInfo) ~
            inboxRoutes ~
            outboxRoutes ~
            sentRoutes ~
            logRoutes ~
            systemRoutes(authInfo) ~
            seriesTypeRoutes(authInfo) ~
            forwardingRoutes(authInfo)
        }
      } ~ remoteBoxRoutes
    } ~ pathPrefixTest(!"api") {
      pathPrefix("assets") {
        staticResourcesRoute
      } ~ pathPrefixTest(!"assets") {
        loginRoute ~ faviconRoutes ~ angularRoute
      }
    }

}
