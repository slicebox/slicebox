package se.nimsa.sbx.util

import akka.actor.Actor
import akka.actor.Stash
import scala.concurrent.Future
import scala.util.Success
import scala.util.Failure
import akka.actor.ActorRef
import akka.actor.Status
import scala.concurrent.ExecutionContext

trait SynchronousProcessingActor extends Actor with Stash {

  import SynchronousProcessing._

  def gotoProcessingState() = context.become(processingState)

  def markProcessingFinished() = self ! ProcessingFinished

  def processSynchronously[A](future: Future[A], recipient: ActorRef)(implicit ec: ExecutionContext) = {

    gotoProcessingState()

    future onComplete {
      case Success(r) =>
        markProcessingFinished()
        recipient ! r
      case Failure(f) =>
        markProcessingFinished()
        recipient ! Status.Failure(f)
    }
  }

  def processingState: Receive = {
    case ProcessingFinished =>
      unstashAll()
      context.unbecome()
    case _ =>
      stash()
  }

}

object SynchronousProcessing {
  case object ProcessingFinished
}