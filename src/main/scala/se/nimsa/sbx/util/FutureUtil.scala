/*
 * Copyright 2014 Lars Edenbrandt
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

import akka.util.Timeout

import scala.collection.generic.CanBuildFrom
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.higherKinds

object FutureUtil {

  /**
    * Iterate through the input collection and create a future for each element using the supplied factory function.
    * Each future is created and completed in sequence, guaranteeing that futures never run in parallel. This is the
    * sequential equivalent of `Future.traverse`.
    *
    * @param in The collection of elements to iterate over
    * @param fn The future factory to be called for each element
    * @return The future list of results, one for each input element.
    */
  def traverseSequentially[A, B, M[X] <: TraversableOnce[X]](in: M[A])(fn: A => Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]], executor: ExecutionContext): Future[M[B]] =
    in.foldLeft(Future.successful(cbf(in))) { (fr, a) =>
      fr.flatMap(r => fn(a).map(b => r += b))
    }.map(_.result())

  /**
    * Block and wait for the input future to finish, or until the supplied timout expires. Use sparingly as this will
    * block the current thread.
    *
    * @param future  Future to wait for
    * @param timeout Maximum wait time before finishing with a timeout exception
    * @return the result of the future.
    */
  def await[T](future: Future[T])(implicit timeout: Timeout): T = Await.result(future, timeout.duration)
}
