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
import spray.http.HttpCookie

class Authenticator(userService: ActorRef) {

  implicit val timeout = Timeout(10.seconds)

  def sliceboxAuthenticator(authKey: AuthKey)(implicit ec: ExecutionContext): AuthMagnet[AuthInfo] = {

    println("creating authenticator with auth key " + authKey)
    
    def validateUser(optionalUserPass: Option[UserPass]): Future[Option[AuthInfo]] = optionalUserPass
      .map(userPass => {
        println("Doing basic auth: " + userPass)
        userService.ask(GetUserByName(userPass.user)).mapTo[Option[ApiUser]].map(_ match {
          case Some(repoUser) if (repoUser.passwordMatches(userPass.pass)) =>
            Some(new AuthInfo(repoUser))
          case _ =>
            None
        })})
      .getOrElse {
        println("Doing token auth: " + authKey)
        userService.ask(GetAndRefreshUserByAuthKey(authKey)).mapTo[Option[ApiUser]].map(
          _.map(AuthInfo(_)))
      }

    def authenticator(userPass: Option[UserPass]): Future[Option[AuthInfo]] = validateUser(userPass)

    BasicAuth(authenticator _, realm = "Slicebox")
  }
}
