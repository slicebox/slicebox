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

trait UserRoutes { this: RestApi =>

  def loginRoute: Route =
    path("login") {
      post {
        entity(as[UserPass]) { userPass =>
          onSuccess(userService.ask(GetUserByName(userPass.user)).mapTo[Option[ApiUser]]) {
            case Some(repoUser) if (repoUser.passwordMatches(userPass.pass)) =>
              setCookie(HttpCookie("slicebox-user", content = repoUser.user)) {
                complete(LoginResult(true, repoUser.role, s"User ${userPass.user} logged in"))
              }
            case _ =>
              complete(LoginResult(false, UserRole.USER, "Incorrect username or password"))
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
