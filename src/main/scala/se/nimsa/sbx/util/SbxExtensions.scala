package se.nimsa.sbx.util

import scala.concurrent.Future
import scala.concurrent.ExecutionContext

object SbxExtensions {

  implicit class FutureOptionFuture[T](val self: Future[Option[Future[T]]]) extends AnyVal {
    def unwrap(implicit ec: ExecutionContext): Future[Option[T]] =
      self.flatMap(of => of.map(_.map(Some(_))).getOrElse(Future(None)))
  }

  implicit class OptionFutureOption[T](val self: Option[Future[Option[T]]]) extends AnyVal {
    def unwrap: Future[Option[T]] =
      self.getOrElse(Future.successful(None))
  }

}