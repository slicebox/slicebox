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

import java.util.concurrent.ThreadLocalRandom

import akka.actor.Scheduler
import akka.pattern.after
import akka.util.Timeout

import scala.collection.generic.CanBuildFrom
import scala.concurrent.duration.{DurationLong, FiniteDuration}
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
    * Run the supplied future and if it fails, retry once for each supplied delay. If a retry is successful, the
    * corresponding result will be reported and no more retries will be attempted. If none of the retries was
    * successful, the last (failed) future will be returned.
    *
    * @param delays       Time duration to wait before each retry. The number of delays determine the number of retries
    *                     before reporting the future as failed.
    * @param randomFactor Add jitter to each delay to mitigate risk of several processes hitting a resource at the exact
    *                     same time. A random factor `x` leads to the delay `(1 + x) * d`, where `d` is the current
    *                     delay. For instance, a random factor of 0.2 will add up to 20% of the current delay.
    * @param shouldRetry  A partial function that determines if a retry should be attempted based on the exception from
    *                     the previous attempt. Should return `true` to retry. If this function either does not cover
    *                     the current exception or returns `false`, no more retries will be attempted.
    * @param f            Factory function for the future to attempt
    * @return The result of the last attempted future, successful or not
    */
  def retry[T](delays: Seq[FiniteDuration], randomFactor: Double)(shouldRetry: PartialFunction[Throwable, Boolean])(f: => Future[T])(implicit ec: ExecutionContext, s: Scheduler): Future[T] = {
    f.recoverWith {
      case t: Throwable if shouldRetry(t) && delays.nonEmpty =>

        val random = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
        val delay = delays.head * random match {
          case f: FiniteDuration => f
          case _ => delays.head
        }

        after(delay, s)(retry(delays.tail, randomFactor)(shouldRetry)(f))
    }
  }

  /**
    * Run the supplied future and if it fails, retry up to `n` times. If a retry is successful, the
    * corresponding result will be reported and no more retries will be attempted. If none of the retries was
    * successful, the last (failed) future will be returned.
    *
    * @param n           Maximum umber of retries
    * @param minBackoff  Backoff time until first retry. Duration is then doubled for each retry.
    * @param shouldRetry A partial function that determines if a retry should be attempted based on the exception from
    *                    the previous attempt. Should return `true` to retry. If this function either does not cover
    *                    the current exception or returns `false`, no more retries will be attempted.
    * @param f           Factory function for the future to attempt
    * @return The result of the last attempted future, successful or not
    */
  def retry[T](n: Int, minBackoff: FiniteDuration = 200.millis, randomFactor: Double)(shouldRetry: PartialFunction[Throwable, Boolean])(f: => Future[T])(implicit ec: ExecutionContext, s: Scheduler): Future[T] =
    retry(backoffDelays(n, minBackoff), randomFactor)(shouldRetry)(f)

  def retry[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double)(f: => Future[T])(implicit ec: ExecutionContext, s: Scheduler): Future[T] =
    retry(minBackoff, maxBackoff, randomFactor, 1)(f)

  private def retry[T](minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double, attempt: Int)(f: => Future[T])(implicit ec: ExecutionContext, s: Scheduler): Future[T] = {
    f.recoverWith {
      case _: Throwable =>
        val delay = calculateDelay(attempt, minBackoff, maxBackoff, randomFactor)

        after(delay, s)(retry(minBackoff, maxBackoff, randomFactor, attempt + 1)(f))
    }
  }

  private def calculateDelay(restartCount: Int,
                             minBackoff: FiniteDuration,
                             maxBackoff: FiniteDuration,
                             randomFactor: Double): FiniteDuration = {
    val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
    if (restartCount >= 30) // Duration overflow protection (> 100 years)
      maxBackoff
    else
      maxBackoff.min(minBackoff * math.pow(2, restartCount)) * rnd match {
        case f: FiniteDuration ⇒ f
        case _ => maxBackoff
      }
  }

  def backoffDelays(n: Int, minBackoff: FiniteDuration): Seq[FiniteDuration] =
    (0 until n).map(i => minBackoff.toMillis * math.pow(2, i)).map(_.toLong.millis)

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
