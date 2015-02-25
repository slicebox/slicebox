package se.vgregion.log

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.meta.MTable
import LogProtocol._
import java.util.Date

class LogDAO(val driver: JdbcProfile) {
  import driver.simple._

  val toLogEntry = (id: Long, created: Long, entryType: String, message: String) => LogEntry(id, created, LogEntryType.withName(entryType), message)
  val fromLogEntry = (logEntry: LogEntry) => Option((logEntry.id, logEntry.created, logEntry.entryType.toString(), logEntry.message))
  
  class LogTable(tag: Tag) extends Table[LogEntry](tag, "Log") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def created = column[Long]("created")
    def entryType = column[String]("type")
    def message = column[String]("message")
    def * = (id, created, entryType, message) <> (toLogEntry.tupled, fromLogEntry)
  }
  
  val logQuery = TableQuery[LogTable]
  
  def create(implicit session: Session): Unit =
    if (MTable.getTables("Log").list.isEmpty) {
      (logQuery.ddl).create
    }

  def drop(implicit session: Session): Unit =
    (logQuery.ddl).drop

  def insertLogEntry(logEntry: LogEntry)(implicit session: Session): LogEntry = {
    val generatedId = (logQuery returning logQuery.map(_.id)) += logEntry
    logEntry.copy(id = generatedId)
  }
  
  def listLogEntries(startIndex: Long, count: Long)(implicit session: Session): List[LogEntry] =
    logQuery
      .sortBy(_.created.desc)
      .drop(startIndex)
      .take(count)
      .list
}