package se.nimsa.sbx.app.routing

import akka.pattern.ask

import spray.http.StatusCodes.Created
import spray.http.StatusCodes.NoContent
import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.nimsa.sbx.app.AuthInfo
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.app.UserProtocol._

trait UserRoutes { this: RestApi =>

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
      } ~ path("generateauthtokens") {
        parameter('n.?(1)) { n =>
          post {
            authenticate(authenticator.basicUserAuthenticator(None)) { authInfo2 => // may not generate tokens using token authentication
              onSuccess(userService.ask(GenerateAuthTokens(authInfo.user, n)).mapTo[List[AuthToken]]) {
                case authTokens =>
                  complete((Created, authTokens))
              }
            }
          }
        }
      }
    }

}