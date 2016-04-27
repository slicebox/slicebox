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

import spray.routing._
import spray.http.StatusCodes._
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.user.UserProtocol._
import spray.routing.ExceptionHandler
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.lang.BadGatewayException

trait SliceboxRoutes extends DirectoryRoutes
    with ScpRoutes
    with ScuRoutes
    with MetadataRoutes
    with ImageRoutes
    with AnonymizationRoutes
    with BoxRoutes
    with TransactionRoutes
    with ForwardingRoutes
    with UserRoutes
    with LogRoutes
    with UiRoutes
    with GeneralRoutes
    with SeriesTypeRoutes
    with ImportRoutes { this: SliceboxService =>

  implicit val knownExceptionHandler =
    ExceptionHandler {
      case e: IllegalArgumentException =>
        complete((BadRequest, e.getMessage()))
      case e: NotFoundException =>
        complete((NotFound, e.getMessage()))
      case e: BadGatewayException =>
        complete((BadGateway, e.getMessage()))
    }

  implicit val authRejectionHandler = RejectionHandler {
    case AuthenticationFailedRejection(cause, headers) :: _ =>
      complete((Unauthorized, "This resource requires authentication. Use either basic auth, or supply the session cookie obtained by logging in."))
  }

  def sliceboxRoutes: Route =
    pathPrefix("api") {
      extractAuthKey { authKey =>
        loginRoute(authKey) ~
          currentUserRoute(authKey) ~
          authenticate(authenticator.newAuthenticator(authKey)) { apiUser =>
            userRoutes(apiUser, authKey) ~
              directoryRoutes(apiUser) ~
              scpRoutes(apiUser) ~
              scuRoutes(apiUser) ~
              metaDataRoutes ~
              imageRoutes(apiUser) ~
              anonymizationRoutes(apiUser) ~
              boxRoutes(apiUser) ~
              logRoutes ~
              generalRoutes(apiUser) ~
              seriesTypeRoutes(apiUser) ~
              forwardingRoutes(apiUser) ~
              importRoutes(apiUser)
          }
      } ~ transactionRoutes
    } ~
      pathPrefixTest(!"api") {
        pathPrefix("assets") {
          staticResourcesRoute
        } ~ pathPrefixTest(!"assets") {
          faviconRoutes ~
            angularRoute
        }
      }

}
