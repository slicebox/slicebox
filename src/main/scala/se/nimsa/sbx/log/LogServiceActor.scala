package se.nimsa.sbx.log

import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.log.LogProtocol._

class LogServiceActor(dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new LogDAO(dbProps.driver)

  setupDb()

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[AddLogEntry])
  }

  override def postStop {
    context.system.eventStream.unsubscribe(context.self)
  }

  def receive = LoggingReceive {
    case AddLogEntry(logEntry) => addLogEntry(logEntry)

    case GetLogEntries(startIndex, count) => sender ! LogEntries(listLogEntries(startIndex, count))
    case GetLogEntriesBySubject(subject, startIndex, count) => sender ! LogEntries(logEntriesBySubject(subject, startIndex, count))
    case GetLogEntriesByType(entryType, startIndex, count) => sender ! LogEntries(logEntriesByType(entryType, startIndex, count))
    case GetLogEntriesBySubjectAndType(subject, entryType, startIndex, count) => sender ! LogEntries(logEntriesBySubjectAndType(subject, entryType, startIndex, count))

    case RemoveLogEntry(id) =>
      removeLogEntry(id)
      sender ! LogEntryRemoved(id)
  }

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def addLogEntry(logEntry: LogEntry): LogEntry =
    db.withSession { implicit session =>
      dao.insertLogEntry(logEntry)
    }

  def removeLogEntry(logId: Long): Unit =
    db.withSession { implicit session =>
      dao.removeLogEntry(logId)
    }

  def listLogEntries(startIndex: Long, count: Long): Seq[LogEntry] =
    db.withSession { implicit session =>
      dao.listLogEntries(startIndex, count)
    }

  def logEntriesBySubject(subject: String, startIndex: Long, count: Long): Seq[LogEntry] =
    db.withSession { implicit session =>
      dao.logEntriesBySubject(subject, startIndex, count)
    }

  def logEntriesByType(entryType: LogEntryType, startIndex: Long, count: Long): Seq[LogEntry] =
    db.withSession { implicit session =>
      dao.logEntriesByType(entryType, startIndex, count)
    }

  def logEntriesBySubjectAndType(subject: String, entryType: LogEntryType, startIndex: Long, count: Long): Seq[LogEntry] =
    db.withSession { implicit session =>
      dao.logEntriesBySubjectAndType(subject, entryType, startIndex, count)
    }
}

object LogServiceActor {
  def props(dbProps: DbProps): Props = Props(new LogServiceActor(dbProps))
}