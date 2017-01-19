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

package se.nimsa.sbx.log

import akka.actor.ActorSystem
import se.nimsa.sbx.log.LogProtocol.AddLogEntry
import se.nimsa.sbx.log.LogProtocol.LogEntry
import java.util.Date
import se.nimsa.sbx.log.LogProtocol.LogEntryType._
import se.nimsa.sbx.log.LogProtocol.LogEntryType
import akka.event.Logging

object SbxLog {

  def info(subject: String, message: String)(implicit system: ActorSystem) = log(subject, message, INFO)

  def warn(subject: String, message: String)(implicit system: ActorSystem) = log(subject, message, WARN)

  def error(subject: String, message: String)(implicit system: ActorSystem) = log(subject, message, ERROR)

  def default(subject: String, message: String)(implicit system: ActorSystem) = log(subject, message, DEFAULT)

  private def log(subject: String, message: String, entryType: LogEntryType)(implicit system: ActorSystem) = {
    val log = Logging(system, "")
    entryType match {
      case INFO    => log.info(s"$subject: $message")
      case DEFAULT => log.debug(s"$subject: $message")
      case WARN    => log.warning(s"$subject: $message")
      case ERROR   => log.error(s"$subject: $message")
    }
    system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, entryType, subject, message)))
  }
}
