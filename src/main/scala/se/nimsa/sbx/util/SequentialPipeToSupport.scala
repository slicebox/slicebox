/*
 * Copyright 2015 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
