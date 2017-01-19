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

package se.nimsa.sbx.user

import akka.actor.ActorRef
import akka.http.scaladsl.server.directives.Credentials
import akka.pattern.ask
import akka.util.Timeout
import se.nimsa.sbx.user.UserProtocol._

import scala.concurrent.{ExecutionContext, Future}

class Authenticator(userService: ActorRef)(implicit ec: ExecutionContext, timeout: Timeout) {

  def apply(authKey: AuthKey)(credentials: Credentials): Future[Option[ApiUser]] =
    credentials match {
      case p @ Credentials.Provided(name) =>
        userService.ask(GetUserByName(name)).mapTo[Option[ApiUser]]
          .map(_.filter(_.passwordMatches(p)))
      case _ =>
        userService.ask(GetAndRefreshUserByAuthKey(authKey)).mapTo[Option[ApiUser]]
    }
}
