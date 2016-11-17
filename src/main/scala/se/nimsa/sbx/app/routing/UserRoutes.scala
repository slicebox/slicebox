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

import akka.pattern.ask
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{HttpHeader, RemoteAddress}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import se.nimsa.sbx.app.SliceboxServices
import se.nimsa.sbx.user.UserProtocol.{AuthKey, _}

trait UserRoutes { this: SliceboxServices =>

  val extractUserAgent: HttpHeader => Option[String] = {
    case a: `User-Agent` => Some(a.value)
    case x               => None
  }

  val extractIP: Directive1[RemoteAddress] =
    headerValuePF {
      case `X-Forwarded-For`(Seq(address, _*)) => address
      case `Remote-Address`(address) => address
      case `X-Real-Ip`(address) => address
    } | provide(RemoteAddress.Unknown)

  def extractAuthKey: Directive1[AuthKey] =
    (optionalCookie(sessionField) & extractIP & optionalHeaderValue(extractUserAgent)).tmap {
      case (optionalCookie, ip, optionalUserAgent) =>
        AuthKey(optionalCookie.map(_.value), ip.toOption.map(_.getHostAddress), optionalUserAgent)
    }

  def loginRoute(authKey: AuthKey): Route =
    path("users" / "login") {
      post {
        entity(as[UserPass]) { userPass =>
          onSuccess(userService.ask(Login(userPass, authKey))) {
            case LoggedIn(user, session) =>
              setCookie(HttpCookie(sessionField, value = session.token, path = Some("/api"), httpOnly = true)) {
                complete(NoContent)
              }
            case LoginFailed =>
              complete((Unauthorized, "Invalid username or password"))
          }
        }
      }
    }

  def currentUserRoute(authKey: AuthKey): Route =
    path("users" / "current") {
      get {
        onSuccess(userService.ask(GetAndRefreshUserByAuthKey(authKey)).mapTo[Option[ApiUser]]) { optionalUser =>
          optionalUser.map(user => UserInfo(user.id, user.user, user.role)) match {
            case Some(userInfo) => complete(userInfo)
            case None           => complete(NotFound)
          }
        }
      }
    }

  def userRoutes(apiUser: ApiUser, authKey: AuthKey): Route =
    pathPrefix("users") {
      pathEndOrSingleSlash {
        get {
          parameters(
            'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
            'count.as(nonNegativeFromStringUnmarshaller) ? 20) { (startIndex, count) =>
            onSuccess(userService.ask(GetUsers(startIndex, count))) {
              case Users(users) =>
                complete(users)
            }
          }
        } ~ post {
          authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
            entity(as[ClearTextUser]) { user =>
              val apiUser = ApiUser(-1, user.user, user.role).withPassword(user.password)
              onSuccess(userService.ask(AddUser(apiUser))) {
                case UserAdded(addedUser) =>
                  complete((Created, addedUser))
              }
            }
          }
        }
      } ~ path(LongNumber) { userId =>
        delete {
          authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
            onSuccess(userService.ask(DeleteUser(userId))) {
              case UserDeleted(_) =>
                complete(NoContent)
            }
          }
        }
      } ~ path("logout") {
        post {
          onSuccess(userService.ask(Logout(apiUser, authKey))) {
            case LoggedOut =>
              deleteCookie(sessionField, path = "/api") {
                complete(NoContent)
              }
          }
        }
      }
    }

}
