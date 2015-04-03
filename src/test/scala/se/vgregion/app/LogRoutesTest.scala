package se.vgregion.app

import scala.concurrent.Future
import spray.http.BasicHttpCredentials
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing._
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.vgregion.app.UserProtocol.AuthToken
import se.vgregion.log.LogProtocol._
import scala.slick.driver.H2Driver
import se.vgregion.log.LogDAO
import java.util.Date
import org.scalatest.BeforeAndAfterAll

class LogRoutesTest extends FlatSpec with Matchers with RoutesTestBase with BeforeAndAfterAll {

  def dbUrl() = "jdbc:h2:mem:logroutestest;DB_CLOSE_DELAY=-1"

  override def beforeAll() {
    super.beforeAll()
    system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.INFO, "Category1", "Message1")))
    system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.INFO, "Category1", "Message2")))
    system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.WARN, "Category1", "Message3")))
    system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.WARN, "Category2", "Message4")))
    system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.DEFAULT, "Category2", "Message5")))
    system.eventStream.publish(AddLogEntry(LogEntry(-1, new Date().getTime, LogEntryType.ERROR, "Category2", "Message6")))
  }
  
  "Log routes" should "support listing log messages" in {
    GetAsUser("/api/log?startindex=0&count=1000") ~> routes ~> check {
      status should be(OK)
      responseAs[List[LogEntry]].size should be(6)
    }
  }
  
  it should "support listing filtered log messages" in {
    GetAsUser("/api/log?startindex=0&count=1000&subject=Category1&type=INFO") ~> routes ~> check {
      status should be(OK)
      responseAs[List[LogEntry]].size should be(2)
    }    
  }
  
  it should "support removing log messages" in {
    DeleteAsUser("/api/log/1") ~> routes ~> check {
      status should be (NoContent)
    }
    
    DeleteAsUser("/api/log/6") ~> routes ~> check {
      status should be (NoContent)
    }
    
    GetAsUser("/api/log?startindex=0&count=1000") ~> routes ~> check {
      status should be(OK)
      responseAs[List[LogEntry]].size should be(4)
    }    
  }
}