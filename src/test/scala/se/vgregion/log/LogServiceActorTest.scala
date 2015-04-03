package se.vgregion.log

import akka.testkit.TestKit
import akka.testkit.ImplicitSender
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import scala.slick.driver.H2Driver
import se.vgregion.app.DbProps
import scala.slick.jdbc.JdbcBackend.Database
import akka.actor.Props
import se.vgregion.log.LogProtocol._
import java.util.Date

class LogServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("LogServiceActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:logserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val logDao = new LogDAO(H2Driver)

  db.withSession { implicit session =>
    logDao.create
  }

  val logServiceActorRef = _system.actorOf(Props(new LogServiceActor(dbProps)))

  override def afterAll = {
    TestKit.shutdownActorSystem(system)
  }

  "A LogServiceActor" should {

    "add log messages pushed to the event stream" in {
      _system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.INFO, "Category1", "Message1")))
      _system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.INFO, "Category1", "Message2")))
      _system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.WARN, "Category1", "Message3")))
      _system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.WARN, "Category2", "Message4")))
      _system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.DEFAULT, "Category2", "Message5")))
      _system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.ERROR, "Category2", "Message6")))

      logServiceActorRef ! GetLogEntries(0, 1000)

      expectMsgPF() {
        case LogEntries(logEntries) if logEntries.size == 6 => true
      }
    }

    "support removing a log message" in {
      logServiceActorRef ! RemoveLogEntry(3)

      expectMsg(LogEntryRemoved(3))

      logServiceActorRef ! GetLogEntries(0, 1000)

      expectMsgPF() {
        case LogEntries(logEntries) if logEntries.size == 5 => true
      }
    }

    "support listing log entries by type" in {
      logServiceActorRef ! GetLogEntriesByType(LogEntryType.INFO, 0, 1000)

      expectMsgPF() {
        case LogEntries(logEntries) if logEntries.size == 2 && logEntries.forall(_.entryType == LogEntryType.INFO) => true
      }
    }

    "support listing log entries by subject" in {
      logServiceActorRef ! GetLogEntriesBySubject("Category2", 0, 1000)

      expectMsgPF() {
        case LogEntries(logEntries) if logEntries.size == 3 && logEntries.forall(_.subject == "Category2") => true
      }
    }

    "support listing log entries by subject and type" in {
      logServiceActorRef ! GetLogEntriesBySubjectAndType("Category2", LogEntryType.DEFAULT, 0, 1000)

      expectMsgPF() {
        case LogEntries(logEntries) if logEntries.size == 1 &&
          logEntries(0).subject == "Category2" &&
          logEntries(0).entryType == LogEntryType.DEFAULT => true
      }
    }

  }
}