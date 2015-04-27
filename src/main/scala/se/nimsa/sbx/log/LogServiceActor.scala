/*
 * Copyright 2015 Karl Sjöstrand
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
    context.system.eventStream.subscribe(self, classOf[AddLogEntry])
  }

  override def postStop {
    context.system.eventStream.unsubscribe(self)
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
