package se.vgregion.box

import akka.event.LoggingReceive
import akka.actor.Actor
import spray.client.pipelining._
import scala.concurrent.duration.DurationInt
import akka.event.Logging
import akka.actor.Props
import BoxProtocol._

class BoxClientActor(config: BoxClientConfig) extends Actor {
  val log = Logging(context.system, this)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val pipeline = sendReceive

  def ip = pipeline(Get("http://bot.whatismyipaddress.com"))

  system.scheduler.schedule(1.second, 5.seconds) {
    ip.map(yourIp =>
      log.info("Hello from box " + config.name + ", your ip is " + yourIp.entity.asString))
  }

  def receive = LoggingReceive {
    case msg =>
  }
}

object BoxClientActor {
  def props(config: BoxClientConfig): Props = Props(new BoxClientActor(config))
}