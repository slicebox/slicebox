package se.vgregion.util

import akka.actor.Actor
import akka.actor.Status.Failure

trait ExceptionCatching { this: Actor =>
  
  def catchAndReport(op: => Unit) = {
    try {
      op
    } catch {
      case e: Exception => sender ! Failure(e)
    }
  }

}