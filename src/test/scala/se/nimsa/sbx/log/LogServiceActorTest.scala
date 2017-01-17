package se.nimsa.sbx.log

import java.util.Date

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import se.nimsa.sbx.log.LogProtocol._
import se.nimsa.sbx.util.FutureUtil.await
import slick.backend.DatabaseConfig
import slick.driver.JdbcProfile

import scala.concurrent.duration.DurationInt

class LogServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("LogServiceActorTestSystem"))

  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(30.seconds)

  val dbConfig = DatabaseConfig.forConfig[JdbcProfile]("slicebox.database.in-memory")
  val db = dbConfig.db

  val logDao = new LogDAO(dbConfig)

  await(logDao.create())

  val logServiceActorRef = _system.actorOf(LogServiceActor.props(logDao))

  override def afterAll = TestKit.shutdownActorSystem(_system)

  "A LogServiceActor" should {

    "add log messages pushed to the event stream" in {
      logServiceActorRef ! AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.INFO, "Category1", "Message1"))
      logServiceActorRef ! AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.INFO, "Category1", "Message2"))
      logServiceActorRef ! AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.WARN, "Category1", "Message3"))
      logServiceActorRef ! AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.WARN, "Category2", "Message4"))
      logServiceActorRef ! AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.DEFAULT, "Category2", "Message5"))
      logServiceActorRef ! AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.ERROR, "Category2", "Message6"))

      expectNoMsg

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
          logEntries.head.subject == "Category2" &&
          logEntries.head.entryType == LogEntryType.DEFAULT => true
      }
    }

  }
}