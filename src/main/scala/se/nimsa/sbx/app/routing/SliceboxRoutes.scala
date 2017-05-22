/*
 * Copyright 2017 Lars Edenbrandt
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

import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.AuthenticationFailedRejection.{CredentialsMissing, CredentialsRejected}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{AuthenticationFailedRejection, ExceptionHandler, RejectionHandler, Route}
import org.dcm4che3.io.DicomStreamException
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.lang.{BadGatewayException, NotFoundException}
import se.nimsa.sbx.user.Authenticator

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
  with ImportRoutes {
  this: SliceboxBase =>

  implicit val knownExceptionHandler =
    ExceptionHandler {
      case e: IllegalArgumentException =>
        complete((BadRequest, e.getMessage))
      case e: NotFoundException =>
        complete((NotFound, e.getMessage))
      case e: FileNotFoundException =>
        complete((NotFound, "File not found: " + e.getMessage))
      case e: DicomStreamException =>
        complete((BadRequest, "Invalid DICOM data: " + e.getMessage))
      case e: NoSuchFileException =>
        complete((NotFound, "File not found: " + e.getMessage))
      case e: BadGatewayException =>
        complete((BadGateway, e.getMessage))
    }

  lazy val authenticator = new Authenticator(userService)

  val authenticationFailedWithoutChallenge = RejectionHandler.newBuilder().handle {
    case AuthenticationFailedRejection(cause, _) =>
      val message = cause match {
        case CredentialsMissing => "The resource requires authentication, which was not supplied with the request"
        case CredentialsRejected => "The supplied authentication is invalid"
      }
      complete((Unauthorized, message))
  }.result()

  def routes: Route =
    pathPrefix("api") {
      handleRejections(authenticationFailedWithoutChallenge) {
        extractAuthKey { authKey =>
          loginRoute(authKey) ~
            currentUserRoute(authKey) ~
            authenticateBasicAsync(realm = "slicebox", authenticator(authKey)) { apiUser =>
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
        } ~ transactionRoutes ~ healthCheckRoute
      }
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
