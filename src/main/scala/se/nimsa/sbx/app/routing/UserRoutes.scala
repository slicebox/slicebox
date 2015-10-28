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

import akka.pattern.ask
import spray.http.StatusCodes.Created
import spray.http.StatusCodes.NoContent
import spray.httpx.SprayJsonSupport._
import spray.routing._
import se.nimsa.sbx.user.AuthInfo
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.user.UserProtocol._
import spray.routing.authentication.UserPass
import spray.http.HttpCookie
import spray.http.HttpHeader
import spray.http.HttpHeaders.`User-Agent`
import se.nimsa.sbx.user.UserProtocol.AuthKey
import spray.http.HttpHeader
import spray.http.HttpHeaders._
import spray.http.RemoteAddress
import shapeless._

trait UserRoutes { this: RestApi =>

  val sessionField = "slicebox-session"

  val extractUserAgent: HttpHeader => Option[String] = {
    case a: `User-Agent` => Some(a.value)
    case x               => None
  }

  val extractIP: Directive1[RemoteAddress] =
    headerValuePF { case `X-Forwarded-For`(Seq(address, _*)) => address } |
      headerValuePF { case `Remote-Address`(address) => address } |
      headerValuePF { case h if h.is("x-real-ip") => RemoteAddress(h.value) } |
      provide(RemoteAddress.Unknown)

  val extractAuthKey: Directive1[AuthKey] =
    (optionalHeaderValue(extractUserAgent) & extractIP & optionalCookie(sessionField)).hmap {
      case optionalUserAgent :: ip :: optionalCookie :: HNil =>
        AuthKey(optionalCookie.map(_.content), ip.toOption.map(_.getHostAddress), optionalUserAgent)
    }

  def loginRoute: Route =
    path("login") {
      post {
        entity(as[UserPass]) { userPass =>
          onSuccess(userService.ask(GetUserByName(userPass.user)).mapTo[Option[ApiUser]]) {
            case Some(repoUser) if (repoUser.passwordMatches(userPass.pass)) =>
              extractAuthKey { authKey =>
                onSuccess(userService.ask(CreateOrUpdateSession(repoUser, authKey.ip, authKey.userAgent)).mapTo[ApiSession]) { session =>
                  setCookie(HttpCookie(sessionField, content = session.token)) {
                    complete(LoginResult(true, repoUser.role, s"User ${userPass.user} logged in"))
                  }
                }
              }
            case _ =>
              complete(LoginResult(false, UserRole.USER, "Incorrect username or password"))
          }
        }
      }
    }

  def logoutRoute(authInfo: AuthInfo): Route =
    path("logout") {
      post {
        extractAuthKey { authKey =>
          onSuccess(userService.ask(DeleteSession(authInfo.user, authKey.ip, authKey.userAgent))) {
            case SessionDeleted(userId) =>
              complete(NoContent)
          }
        }
      }

    }

  def userRoutes(authInfo: AuthInfo): Route =
    pathPrefix("users") {
      pathEndOrSingleSlash {
        get {
          onSuccess(userService.ask(GetUsers)) {
            case Users(users) =>
              complete(users)
          }
        } ~ post {
          authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
            entity(as[ClearTextUser]) { user =>
              val apiUser = ApiUser(-1, user.user, user.role).withPassword(user.password)
              onSuccess(userService.ask(AddUser(apiUser))) {
                case UserAdded(user) =>
                  complete((Created, user))
              }
            }
          }
        }
      } ~ path(LongNumber) { userId =>
        delete {
          authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
            onSuccess(userService.ask(DeleteUser(userId))) {
              case UserDeleted(userId) =>
                complete(NoContent)
            }
          }
        }
      }
    }

}
