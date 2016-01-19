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

import scala.concurrent.Await
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt

import akka.actor.ActorContext
import akka.actor.PoisonPill
import akka.pattern.ask
import akka.pattern.gracefulStop
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.box.BoxProtocol.Boxes
import se.nimsa.sbx.box.BoxProtocol.GetBoxes
import se.nimsa.sbx.directory.DirectoryWatchProtocol.GetWatchedDirectories
import se.nimsa.sbx.directory.DirectoryWatchProtocol.WatchedDirectories
import se.nimsa.sbx.scp.ScpProtocol._
import se.nimsa.sbx.scu.ScuProtocol._
import se.nimsa.sbx.user.UserProtocol._
import spray.httpx.SprayJsonSupport._
import spray.routing.Route

trait GeneralRoutes { this: SliceboxService =>

  def generalRoutes(apiUser: ApiUser): Route =
    pathPrefix("system") {
      path("stop") {
        post {
          authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
            complete {
              val stop =
                gracefulStop(forwardingService, 5.seconds, PoisonPill) andThen
                  { case _ => gracefulStop(directoryService, 5.seconds, PoisonPill) } andThen
                  { case _ => gracefulStop(scpService, 5.seconds, PoisonPill) } andThen
                  { case _ => gracefulStop(scuService, 5.seconds, PoisonPill) } andThen
                  { case _ => gracefulStop(seriesTypeService, 5.seconds, PoisonPill) } andThen
                  { case _ => gracefulStop(logService, 5.seconds, PoisonPill) } andThen
                  { case _ => gracefulStop(storageService, 5.seconds, PoisonPill) } andThen
                  { case _ => gracefulStop(metaDataService, 5.seconds, PoisonPill) } andThen
                  { case _ => gracefulStop(boxService, 5.seconds, PoisonPill) } andThen
                  { case _ => gracefulStop(anonymizationService, 5.seconds, PoisonPill) } andThen
                  { case _ => gracefulStop(userService, 5.seconds, PoisonPill) }
              Await.ready(stop, 5.seconds)

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
