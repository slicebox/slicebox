package se.nimsa.sbx.app

import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

import akka.actor.ActorSystem
import akka.actor.Props
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout

import spray.can.Http

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging

object Main extends App with LazyLogging {
  val config = ConfigFactory.load()
  val host = config.getString("http.host")
  val port = config.getInt("http.port")

  implicit val system = ActorSystem("slicebox")

  implicit val timeout = Timeout(70.seconds)
  implicit val ec = system.dispatcher
  
  val api = system.actorOf(Props(new RestInterface()), "httpInterface")
  IO(Http).ask(Http.Bind(listener = api, interface = host, port = port)) onComplete {
    case Success(message) =>
    case Failure(e) =>
      logger.error(s"Could not bind to $host:$port, ${e.getMessage}")    
  }
}
