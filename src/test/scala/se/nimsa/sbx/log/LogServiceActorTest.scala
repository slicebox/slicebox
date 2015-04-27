package se.nimsa.sbx.log

import akka.testkit.TestKit
import akka.testkit.ImplicitSender
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import scala.slick.driver.H2Driver
import se.nimsa.sbx.app.DbProps
import scala.slick.jdbc.JdbcBackend.Database
import akka.actor.Props
import se.nimsa.sbx.log.LogProtocol._
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
  
  val logServiceActorRef = _system.actorOf(LogServiceActor.props(dbProps))

  override def afterAll = {
    TestKit.shutdownActorSystem(_system)
  }

  "A LogServiceActor" should {

    "add log messages pushed to the event stream" in {
      SbxLog.info("Category1", "Message1")
      SbxLog.info("Category1", "Message2")
      SbxLog.warn("Category1", "Message3")
      SbxLog.warn("Category2", "Message4")
      SbxLog.default("Category2", "Message5")
      SbxLog.error("Category2", "Message6")

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