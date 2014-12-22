package se.vgregion.util

import akka.actor._
import akka.actor.SupervisorStrategy.Stop
import spray.http.StatusCodes._
import spray.routing.RequestContext
import akka.actor.OneForOneStrategy
import scala.concurrent.duration._
import spray.http.StatusCode
import spray.httpx.Json4sSupport
import org.json4s.DefaultFormats
import PerRequest._
import akka.event.LoggingReceive

trait PerRequest extends Actor with Json4sSupport {

  import context._

  val json4sFormats = DefaultFormats

  def r: RequestContext
  def target: ActorRef
  def message: RestMessage

  setReceiveTimeout(4.seconds)
  target ! message

  def receive = LoggingReceive {
    case res: RestMessage => complete(OK, res)
    case v: Validation    => complete(BadRequest, v)
    case ReceiveTimeout   => complete(GatewayTimeout, Error("Request timeout"))
    case msg: Any         => complete(InternalServerError, Error("Unhandled internal message: " + msg))
  }

  def complete[T <: AnyRef](status: StatusCode, obj: T) = {
    r.complete((status, obj))
    stop(self)
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e => {
        complete(InternalServerError, Error(e.getMessage))
        Stop
      }
    }
}

object PerRequest {
  case class WithActorRef(r: RequestContext, target: ActorRef, message: RestMessage) extends PerRequest

  case class WithProps(r: RequestContext, props: Props, message: RestMessage) extends PerRequest {
    lazy val target = context.actorOf(props)
  }
}

trait PerRequestCreator {

  def perRequest(r: RequestContext, target: ActorRef, message: RestMessage)(implicit factory: ActorRefFactory) =
    factory.actorOf(Props(new WithActorRef(r, target, message)))

  def perRequest(r: RequestContext, props: Props, message: RestMessage)(implicit factory: ActorRefFactory) =
    factory.actorOf(Props(new WithProps(r, props, message)))
}
