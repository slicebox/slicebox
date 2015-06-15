package se.nimsa.sbx.anonymization

import akka.actor.Actor
import akka.event.LoggingReceive
import se.nimsa.sbx.app.DbProps
import akka.actor.Props

class AnonymizationServiceActor(dbProps: DbProps) extends Actor {
  
  def receive = LoggingReceive {
    case msg =>
  }
  
}

object AnonymizationServiceActor {
  def props(dbProps: DbProps): Props = Props(new AnonymizationServiceActor(dbProps))
}