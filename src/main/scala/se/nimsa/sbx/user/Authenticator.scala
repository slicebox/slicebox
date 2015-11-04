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
import spray.routing.authentication._
import spray.http.HttpHeaders._
import akka.pattern.ask
import akka.actor.ActorRef
import akka.util.Timeout
import scala.concurrent.duration.DurationInt
import scala.concurrent.Future
import spray.routing._
import spray.routing.Directives._
import UserProtocol._
import spray.http.HttpCookie
import spray.http.HttpChallenge
import spray.http.HttpRequest
import spray.http._
import spray.util._
import AuthenticationFailedRejection._

class Authenticator(userService: ActorRef)(implicit ec: ExecutionContext, timeout: Timeout) {

  def newAuthenticator(authKey: AuthKey) =
    new SliceboxAuthenticator(userService, authKey)
}

class SliceboxAuthenticator(userService: ActorRef, authKey: AuthKey)(implicit ec: ExecutionContext, timeout: Timeout) extends ContextAuthenticator[ApiUser] {

  def apply(ctx: RequestContext) = {
    val authHeader = ctx.request.headers.findByType[`Authorization`]
    val credentials = authHeader.map { case Authorization(creds) => creds }
    val optionalUserPass = credentials.flatMap {
      case BasicHttpCredentials(user, pass) ⇒ Some(UserPass(user, pass))
      case _                                ⇒ None
    }
    val isSession = optionalUserPass.isEmpty && authKey.isValid
    authenticate(optionalUserPass, ctx) map {
      case Some(user) => Right(user)
      case None =>
        val cause = if (authHeader.isEmpty && !isSession) CredentialsMissing else CredentialsRejected
        Left(AuthenticationFailedRejection(cause, getChallengeHeaders(ctx.request, isSession)))
    }
  }

  def authenticate(optionalUserPass: Option[UserPass], ctx: RequestContext): Future[Option[ApiUser]] = {
    optionalUserPass
      .map(userPass =>
        userService.ask(GetUserByName(userPass.user)).mapTo[Option[ApiUser]]
          .map(_.filter(_.passwordMatches(userPass.pass))))
      .getOrElse(
        userService.ask(GetAndRefreshUserByAuthKey(authKey)).mapTo[Option[ApiUser]])
  }

  def getChallengeHeaders(httpRequest: HttpRequest, isSession: Boolean): List[HttpHeader] =
    if (isSession)
      Nil
    else
      `WWW-Authenticate`(HttpChallenge(scheme = "Basic", realm = "Slicebox", params = Map.empty)) :: Nil
      
}