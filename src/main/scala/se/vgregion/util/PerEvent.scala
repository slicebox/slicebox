package se.vgregion.util

import akka.actor._
import akka.actor.SupervisorStrategy.Stop
import spray.http.StatusCodes._
import akka.actor.OneForOneStrategy
import scala.concurrent.duration._
import spray.http.StatusCode
import PerEvent._
import akka.event.Logging

trait PerEvent extends Actor {
  val log = Logging(context.system, this)

  import context._

  def target: ActorRef
  def message: Any

  setReceiveTimeout(4.seconds)
  target ! message

  def receive = {
    case EventInfoMessage(message)    => log.info(message); stop(self)
    case EventWarningMessage(message) => log.warning(message); stop(self)
    case EventErrorMessage(message)   => log.error(message); stop(self)
    case ReceiveTimeout               => log.error("Event timeout"); stop(self)
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e => {
        log.error("Event error: " + e.getMessage)
        Stop
      }
    }
}

object PerEvent {

  case class WithActorRef(target: ActorRef, message: Any) extends PerEvent

  case class WithProps(props: Props, message: Any) extends PerEvent {
    lazy val target = context.actorOf(props)
  }
}

trait PerEventCreator { this: Actor =>

  def perEvent(target: ActorRef, message: Any) = context.actorOf(Props(new WithActorRef(target, message)))

  def perEvent(props: Props, message: Any) = context.actorOf(Props(new WithProps(props, message)))
}
