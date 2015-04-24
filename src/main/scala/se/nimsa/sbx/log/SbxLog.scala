package se.nimsa.sbx.log

import akka.actor.ActorSystem
import se.nimsa.sbx.log.LogProtocol.AddLogEntry
import se.nimsa.sbx.log.LogProtocol.LogEntry
import java.util.Date
import se.nimsa.sbx.log.LogProtocol.LogEntryType._
import se.nimsa.sbx.log.LogProtocol.LogEntryType

object SbxLog {

  def info(subject: String, message: String)(implicit system: ActorSystem) = log(subject, message, INFO)

  def warn(subject: String, message: String)(implicit system: ActorSystem) = log(subject, message, WARN)

  def error(subject: String, message: String)(implicit system: ActorSystem) = log(subject, message, ERROR)

  def default(subject: String, message: String)(implicit system: ActorSystem) = log(subject, message, DEFAULT)

  private def log(subject: String, message: String, entryType: LogEntryType)(implicit system: ActorSystem) = 
    system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, entryType, subject, message)))
}