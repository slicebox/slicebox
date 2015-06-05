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

package se.nimsa.sbx.log

import se.nimsa.sbx.model.Entity
import java.util.Date

object LogProtocol {

  sealed trait LogEntryType {
    override def toString(): String = this match {
      case LogEntryType.DEFAULT => "DEFAULT"
      case LogEntryType.INFO => "INFO"
      case LogEntryType.WARN => "WARN"
      case LogEntryType.ERROR => "ERROR"
    }
  }

  object LogEntryType {
    case object DEFAULT extends LogEntryType
    case object INFO extends LogEntryType
    case object WARN extends LogEntryType
    case object ERROR extends LogEntryType

    def withName(string: String) = string match {
      case "DEFAULT" => DEFAULT
      case "INFO" => INFO
      case "WARN" => WARN
      case "ERROR" => ERROR
    }    
  }
  
  case class LogEntry(id: Long, created: Long, entryType: LogEntryType, subject: String, message: String) extends Entity
  
  // Messages
  case class AddLogEntry(logEntry: LogEntry)
  
  case class GetLogEntries(startIndex: Long, count: Long)
  case class GetLogEntriesBySubject(subject: String, startIndex: Long, count: Long)
  case class GetLogEntriesByType(entryType: LogEntryType, startIndex: Long, count: Long)
  case class GetLogEntriesBySubjectAndType(subject: String, entryType: LogEntryType, startIndex: Long, count: Long)

  case class RemoveLogEntry(logId: Long)
  
  case class LogEntries(logEntries: Seq[LogEntry])
  
  case class LogEntryRemoved(logId: Long)
}
