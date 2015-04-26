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

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.meta.MTable
import LogProtocol._
import java.util.Date

class LogDAO(val driver: JdbcProfile) {
  import driver.simple._

  val toLogEntry = (id: Long, created: Long, entryType: String, subject: String, message: String) => LogEntry(id, created, LogEntryType.withName(entryType), subject, message)
  val fromLogEntry = (logEntry: LogEntry) => Option((logEntry.id, logEntry.created, logEntry.entryType.toString(), logEntry.subject, logEntry.message))

  class LogTable(tag: Tag) extends Table[LogEntry](tag, "Log") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def created = column[Long]("created")
    def entryType = column[String]("type")
    def subject = column[String]("subject")
    def message = column[String]("message")
    def * = (id, created, entryType, subject, message) <> (toLogEntry.tupled, fromLogEntry)
  }

  val logQuery = TableQuery[LogTable]

  def create(implicit session: Session): Unit =
    if (MTable.getTables("Log").list.isEmpty) {
      logQuery.ddl.create
    }

  def drop(implicit session: Session): Unit =
    (logQuery.ddl).drop

  def insertLogEntry(logEntry: LogEntry)(implicit session: Session): LogEntry = {
    val generatedId = (logQuery returning logQuery.map(_.id)) += logEntry
    logEntry.copy(id = generatedId)
  }

  def removeLogEntry(logId: Long)(implicit session: Session): Unit =
    logQuery.filter(_.id === logId).delete

  def listLogEntries(startIndex: Long, count: Long)(implicit session: Session): List[LogEntry] =
    logQuery
      .sortBy(_.created.desc)
      .drop(startIndex)
      .take(count)
      .list

  def logEntriesBySubject(subject: String, startIndex: Long, count: Long)(implicit session: Session): List[LogEntry] =
    logQuery
      .sortBy(_.created.desc)
      .drop(startIndex)
      .take(count)
      .filter(_.subject === subject)
      .list

  def logEntriesByType(entryType: LogEntryType, startIndex: Long, count: Long)(implicit session: Session): List[LogEntry] =
    logQuery
      .sortBy(_.created.desc)
      .drop(startIndex)
      .take(count)
      .filter(_.entryType === entryType.toString)
      .list

  def logEntriesBySubjectAndType(subject: String, entryType: LogEntryType, startIndex: Long, count: Long)(implicit session: Session): List[LogEntry] =
    logQuery
      .sortBy(_.created.desc)
      .drop(startIndex)
      .take(count)
      .filter(_.subject === subject)
      .filter(_.entryType === entryType.toString)
      .list

}
