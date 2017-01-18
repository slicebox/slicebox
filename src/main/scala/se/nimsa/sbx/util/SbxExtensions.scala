/*
 * Copyright 2017 Lars Edenbrandt
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
