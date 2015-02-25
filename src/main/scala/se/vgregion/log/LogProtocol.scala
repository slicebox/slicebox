package se.vgregion.log

import se.vgregion.model.Entity
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
  
  case class LogEntry(id: Long, created: Long, entryType: LogEntryType, message: String) extends Entity
  
  // Messages
  case class AddLogEntry(logEntry: LogEntry)
  case class GetLogEntries(startIndex: Long, count: Long)
  
  case class LogEntries(logEntries: Seq[LogEntry])
}