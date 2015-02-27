package se.vgregion.log

import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.app.DbProps
import se.vgregion.log.LogProtocol._

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
  }
  
  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }
  
  def addLogEntry(logEntry: LogEntry): LogEntry =
    db.withSession { implicit session =>
      dao.insertLogEntry(logEntry)
    }
  
  def listLogEntries(startIndex: Long, count: Long): Seq[LogEntry] =
    db.withSession { implicit session =>
      dao.listLogEntries(startIndex, count)
    }
}

object LogServiceActor {
  def props(dbProps: DbProps): Props = Props(new LogServiceActor(dbProps))
}