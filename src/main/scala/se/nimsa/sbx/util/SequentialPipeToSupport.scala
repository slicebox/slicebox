package se.nimsa.sbx.util

import akka.actor.Actor
import akka.actor.Stash
import scala.concurrent.Future
import scala.util.Success
import scala.util.Failure
import akka.actor.ActorRef
import akka.actor.Status
import scala.concurrent.ExecutionContext
import language.implicitConversions

trait SequentialPipeToSupport { this: Actor with Stash =>

  import SynchronousProcessing._

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

object SynchronousProcessing {
  case object ProcessingFinished
}