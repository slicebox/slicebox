/*
 * Copyright 2016 Lars Edenbrandt
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

import akka.actor.Actor
import akka.actor.Status.Failure
import se.nimsa.sbx.log.SbxLog

trait ExceptionCatching { this: Actor =>

  def catchAndReport[A](op: => A): Option[A] =
    try {
      Some(op)
    } catch {
      case e: Exception =>
        if (e.isInstanceOf[IllegalArgumentException]) 
           SbxLog.info("System", "Bad request caused error: " + e.getMessage)(context.system)
         else
        	 SbxLog.error("System", "" + e.getMessage)(context.system)
        sender ! Failure(e)
        None
    }

  def catchReportAndThrow[A](op: => A): A =
    try {
      op
    } catch {
      case e: Exception =>
        if (e.isInstanceOf[IllegalArgumentException]) 
           SbxLog.info("System", "Bad request caused error: " + e.getMessage)(context.system)
         else
           SbxLog.error("System", "" + e.getMessage)(context.system)
        sender ! Failure(e)
        throw e
    }

}
