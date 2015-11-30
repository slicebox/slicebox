package se.nimsa.sbx.util

import scala.language.higherKinds
import scala.collection.generic.CanBuildFrom
import scala.concurrent.Future
import scala.concurrent.ExecutionContext

object FutureUtil {

  def traverseSequentially[A, B, M[X] <: TraversableOnce[X]](in: M[A])(fn: A => Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]], executor: ExecutionContext): Future[M[B]] =
    in.foldLeft(Future.successful(cbf(in))) { (fr, a) =>
      fr.flatMap(r => fn(a).map(b => r += b))
    }.map(_.result())

}