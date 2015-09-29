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

package se.nimsa.sbx.user

import scala.concurrent.ExecutionContext
import spray.routing.authentication.BasicAuth
import spray.routing.authentication.UserPass
import spray.routing.directives.AuthMagnet
import spray.routing.directives.AuthMagnet.fromContextAuthenticator
import akka.pattern.ask
import akka.actor.ActorRef
import akka.util.Timeout
import scala.concurrent.duration.DurationInt
import scala.concurrent.Future
import spray.routing.Directives._
import UserProtocol._

class Authenticator(userService: ActorRef) {

  implicit val timeout = Timeout(10.seconds)

  def basicUserAuthenticator(optionalAuthToken: Option[String])(implicit ec: ExecutionContext): AuthMagnet[AuthInfo] = {

    def validateUser(optionalUserPass: Option[UserPass]): Future[Option[AuthInfo]] = {
      val optionalFutureAuthInfo =
        if (optionalAuthToken.isDefined)
          optionalAuthToken.map(authToken =>
            userService.ask(GetUserByAuthToken(AuthToken(authToken))).mapTo[Option[ApiUser]].map(_.map(AuthInfo(_))))
        else
          optionalUserPass.map(userPass =>
            userService.ask(GetUserByName(userPass.user)).mapTo[Option[ApiUser]].map {
              case Some(repoUser) if (repoUser.passwordMatches(userPass.pass)) => Some(new AuthInfo(repoUser))
              case _ => None
            })
      optionalFutureAuthInfo.getOrElse(Future.successful(None))
    }

    def authenticator(userPass: Option[UserPass]): Future[Option[AuthInfo]] = validateUser(userPass)

    BasicAuth(authenticator _, realm = "Slicebox")
  }
}
