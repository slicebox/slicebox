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

package se.nimsa.sbx.log

import se.nimsa.sbx.log.LogProtocol._
import se.nimsa.sbx.util.DbUtil.createTables
import slick.backend.DatabaseConfig
import slick.driver.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

class LogDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext){

  import dbConf.driver.api._

  val db = dbConf.db

  val toLogEntry = (id: Long, created: Long, entryType: String, subject: String, message: String) => LogEntry(id, created, LogEntryType.withName(entryType), subject, message)
  val fromLogEntry = (logEntry: LogEntry) => Option((logEntry.id, logEntry.created, logEntry.entryType.toString(), logEntry.subject, logEntry.message))

  class LogTable(tag: Tag) extends Table[LogEntry](tag, LogTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def created = column[Long]("created")
    def entryType = column[String]("type")
    def subject = column[String]("subject")
    def message = column[String]("message")
    def * = (id, created, entryType, subject, message) <> (toLogEntry.tupled, fromLogEntry)
  }

  object LogTable {
    val name = "Log"
  }

  val logQuery = TableQuery[LogTable]

  def create() = createTables(dbConf, Seq((LogTable.name, logQuery)))

  def drop() = db.run(logQuery.schema.drop)

  def clear() = db.run(logQuery.delete)

  def insertLogEntry(logEntry: LogEntry): Future[LogEntry] = db.run {
    (logQuery returning logQuery.map(_.id) += logEntry)
      .map(generatedId => logEntry.copy(id = generatedId))
  }

  def removeLogEntry(logId: Long): Future[Unit] = db.run {
    logQuery.filter(_.id === logId).delete.map(_ => {})
  }

  def listLogEntries(startIndex: Long, count: Long): Future[Seq[LogEntry]] = db.run {
    logQuery
      .sortBy(_.created.desc)
      .drop(startIndex)
      .take(count)
      .result
  }

  def logEntriesBySubject(subject: String, startIndex: Long, count: Long): Future[Seq[LogEntry]] = db.run {
    logQuery
      .filter(_.subject === subject)
      .sortBy(_.created.desc)
      .drop(startIndex)
      .take(count)
      .result
  }

  def logEntriesByType(entryType: LogEntryType, startIndex: Long, count: Long): Future[Seq[LogEntry]] = db.run {
    logQuery
      .filter(_.entryType === entryType.toString)
      .sortBy(_.created.desc)
      .drop(startIndex)
      .take(count)
      .result
  }

  def logEntriesBySubjectAndType(subject: String, entryType: LogEntryType, startIndex: Long, count: Long): Future[Seq[LogEntry]] = db.run {
    logQuery
      .filter(_.subject === subject)
      .filter(_.entryType === entryType.toString)
      .sortBy(_.created.desc)
      .drop(startIndex)
      .take(count)
      .result
  }

}
