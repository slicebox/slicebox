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

import scala.concurrent.duration.DurationInt

import akka.actor.ActorContext
import akka.pattern.ask
import se.nimsa.sbx.app.AuthInfo
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.app.UserProtocol.GetUsers
import se.nimsa.sbx.app.UserProtocol.UserRole
import se.nimsa.sbx.app.UserProtocol.Users
import se.nimsa.sbx.box.BoxProtocol.Boxes
import se.nimsa.sbx.box.BoxProtocol.GetBoxes
import se.nimsa.sbx.directory.DirectoryWatchProtocol.GetWatchedDirectories
import se.nimsa.sbx.directory.DirectoryWatchProtocol.WatchedDirectories
import se.nimsa.sbx.scp.ScpProtocol.GetScps
import se.nimsa.sbx.scp.ScpProtocol.Scps
import se.nimsa.sbx.scu.ScuProtocol.GetScus
import se.nimsa.sbx.scu.ScuProtocol.Scus
import se.nimsa.sbx.storage.StorageProtocol.Destination
import se.nimsa.sbx.storage.StorageProtocol.DestinationType
import se.nimsa.sbx.storage.StorageProtocol.Source
import se.nimsa.sbx.storage.StorageProtocol.SourceType
import spray.httpx.SprayJsonSupport._
import spray.routing.Route

trait GeneralRoutes { this: RestApi =>

  def generalRoutes(authInfo: AuthInfo): Route =
    pathPrefix("system") {
      path("stop") {
        post {
          authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
            complete {
              val system = actorRefFactory.asInstanceOf[ActorContext].system
              system.scheduler.scheduleOnce(1.second)(system.shutdown())(system.dispatcher)
              "Shutting down in 1 second..."
            }
          }
        }
      }
    } ~ path("sources") {
      get {
        def futureSources =
          for {
            users <- userService.ask(GetUsers).mapTo[Users]
            boxes <- boxService.ask(GetBoxes).mapTo[Boxes]
            scps <- scpService.ask(GetScps).mapTo[Scps]
            dirs <- directoryService.ask(GetWatchedDirectories).mapTo[WatchedDirectories]
          } yield {
            users.users.map(user => Source(SourceType.USER, user.user, user.id)) ++
              boxes.boxes.map(box => Source(SourceType.BOX, box.name, box.id)) ++
              scps.scps.map(scp => Source(SourceType.SCP, scp.name, scp.id)) ++
              dirs.directories.map(dir => Source(SourceType.DIRECTORY, dir.name, dir.id))
          }
        onSuccess(futureSources) {
          complete(_)
        }
      }
    } ~ path("destinations") {
      get {
        def futureDestinations =
          for {
            boxes <- boxService.ask(GetBoxes).mapTo[Boxes]
            scus <- scuService.ask(GetScus).mapTo[Scus]
          } yield {
            boxes.boxes.map(box => Destination(DestinationType.BOX, box.name, box.id)) ++
              scus.scus.map(scu => Destination(DestinationType.SCU, scu.name, scu.id))
          }
        onSuccess(futureDestinations) {
          complete(_)
        }
      }
    }
}
