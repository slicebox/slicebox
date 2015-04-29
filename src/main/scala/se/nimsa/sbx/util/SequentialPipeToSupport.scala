package se.nimsa.sbx.util

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.language.implicitConversions
import scala.util.Failure
import scala.util.Success

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Stash
import akka.actor.Status

trait SequentialPipeToSupport { this: Actor with Stash =>

  case object ProcessingFinished

  def gotoProcessingState() = context.become(processingState)

  def markProcessingFinished() = self ! ProcessingFinished

  def processingState: Receive = {
    case ProcessingFinished =>
      unstashAll()
      context.unbecome()
    case _ =>
      stash()
  }

  final class SequentialPipeableFuture[T](val future: Future[T])(implicit executionContext: ExecutionContext) {

    def pipeSequentiallyTo(recipient: ActorRef): Future[T] = {

      gotoProcessingState()

      future onComplete {
        case Success(r) =>
          markProcessingFinished()
          recipient ! r
        case Failure(f) =>
          markProcessingFinished()
          recipient ! Status.Failure(f)
      }
      
      future
    }
  }

  implicit def pipeSequentially[T](future: Future[T])(implicit executionContext: ExecutionContext) = 
    new SequentialPipeableFuture(future)
  
}
