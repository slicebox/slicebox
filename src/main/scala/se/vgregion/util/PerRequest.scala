package se.vgregion.util

import scala.concurrent.duration.DurationInt
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.OneForOneStrategy
import akka.actor.ReceiveTimeout
import akka.actor.SupervisorStrategy.Stop
import akka.event.LoggingReceive
import spray.http.StatusCode
import spray.http.StatusCodes._
import spray.json.RootJsonFormat
import spray.routing.RequestContext
import spray.httpx.SprayJsonSupport._
import akka.event.Logging

abstract class PerRequest(val r: RequestContext, val target: ActorRef, val message: Any) extends Actor {
  val log = Logging(context.system, this)

  import context._

  setReceiveTimeout(4.seconds)
  target ! message

  def receive = handleResponse orElse handleError
  
  def handleResponse: PartialFunction[Any, Unit]
  
  def handleError: PartialFunction[Any, Unit] = LoggingReceive {
    case v: ClientError    => complete(BadRequest, v)
    case ReceiveTimeout   => complete(GatewayTimeout, ServerError("Request timeout"))
    case msg: Any         => complete(InternalServerError, ServerError("Unhandled internal message: " + msg))
  }

  def complete(status: StatusCode, message: String) = {
    log.debug(s"Message to client: $message")
    r.complete((status, message))
    stop(self)
  }

  def complete[M <: AnyRef: RootJsonFormat](status: StatusCode, entity: M) = {
    log.debug(s"Entity to client: $entity")
    r.complete((status, entity))
    stop(self)
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e => {
        complete(InternalServerError, ServerError(e.getMessage))
        Stop
      }
    }
}
