package se.vgregion.app

import com.typesafe.config.ConfigFactory

import akka.actor.ActorSystem
import akka.actor.Props
import akka.io.IO
import spray.can.Http

object Main extends App {
  val config = ConfigFactory.load()
  val host = config.getString("http.host")
  val port = config.getInt("http.port")

  implicit val system = ActorSystem("slicebox")

  val api = system.actorOf(Props(new RestInterface()), "httpInterface")
  IO(Http) ! Http.Bind(listener = api, interface = host, port = port)
}
