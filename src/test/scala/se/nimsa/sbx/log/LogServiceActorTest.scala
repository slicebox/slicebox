package se.nimsa.sbx.log

import java.util.Date

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.sbx.log.LogProtocol._
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt

class LogServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("LogServiceActorTestSystem"))

  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)

  val dbConfig = TestUtil.createTestDb("logserviceactortest")
  val db = dbConfig.db

  val logDao = new LogDAO(dbConfig)

  await(logDao.create())

  val logServiceActorRef: ActorRef = _system.actorOf(LogServiceActor.props(logDao))

  override def beforeEach(): Unit = {
    logServiceActorRef ! AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.INFO, "Category1", "Message1"))
    expectMsgType[LogEntryAdded]
    logServiceActorRef ! AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.INFO, "Category1", "Message2"))
    expectMsgType[LogEntryAdded]
    logServiceActorRef ! AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.WARN, "Category1", "Message3"))
    expectMsgType[LogEntryAdded]
    logServiceActorRef ! AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.WARN, "Category2", "Message4"))
    expectMsgType[LogEntryAdded]
    logServiceActorRef ! AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.DEFAULT, "Category2", "Message5"))
    expectMsgType[LogEntryAdded]
    logServiceActorRef ! AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.ERROR, "Category2", "Message6"))
    expectMsgType[LogEntryAdded]
  }

  override def afterEach(): Unit = await(logDao.clear())

  override def afterAll: Unit = TestKit.shutdownActorSystem(_system)

  "A LogServiceActor" should {

    "add log messages pushed to the event stream" in {
      logServiceActorRef ! GetLogEntries(0, 1000)

      expectMsgPF() {
        case LogEntries(logEntries) if logEntries.size == 6 => true
      }
    }

    "support removing a log message" in {
      logServiceActorRef ! GetLogEntries(0, 1000)
      val someEntry = expectMsgType[LogEntries].logEntries.head

      logServiceActorRef ! RemoveLogEntry(someEntry.id)

      expectMsg(LogEntryRemoved(someEntry.id))

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

    "support clearing the log" in {
      logServiceActorRef ! ClearLog
      expectMsg(6)

      logServiceActorRef ! GetLogEntries(0, 1000)
      expectMsgPF() {
        case LogEntries(logEntries) if logEntries.isEmpty => true
      }
    }

  }
}