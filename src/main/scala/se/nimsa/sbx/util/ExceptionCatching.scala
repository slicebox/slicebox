package se.nimsa.sbx.util

import akka.actor.Actor
import akka.actor.Status.Failure

trait ExceptionCatching { this: Actor =>

  def catchAndReport[A](op: => A): Option[A] =
    try {
      Some(op)
    } catch {
      case e: Exception => 
        sender ! Failure(e)
        None
    }

  def catchReportAndThrow[A](op: => A): A =
    try {
      op
    } catch {
      case e: Exception => 
        sender ! Failure(e)
        throw e
    }

}