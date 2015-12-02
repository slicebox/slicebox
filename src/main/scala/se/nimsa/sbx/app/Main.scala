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

package se.nimsa.sbx.app

import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import akka.actor.ActorSystem
import akka.actor.Props
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import spray.can.Http
import se.nimsa.sbx.log.SbxLog

object Main extends App with LazyLogging with SslConfiguration {
  val config = ConfigFactory.load()
  val host = config.getString("slicebox.host")
  val port = config.getInt("slicebox.port")

  implicit val system = ActorSystem("slicebox")

  implicit val timeout = Timeout(70.seconds)
  implicit val ec = system.dispatcher
  val api = system.actorOf(Props(new SliceboxServiceActor()), "SliceboxService")
  IO(Http).ask(Http.Bind(listener = api, interface = host, port = port)) onComplete {
    case Success(message) =>
      SbxLog.info("System", s"Slicebox bound to $host:$port")
    case Failure(e) =>
      SbxLog.error("System", s"Could not bind to $host:$port, ${e.getMessage}")
  }
}
