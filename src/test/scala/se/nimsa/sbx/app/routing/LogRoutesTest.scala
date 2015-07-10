package se.nimsa.sbx.app.routing

import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers

import se.nimsa.sbx.log.LogProtocol.LogEntry
import se.nimsa.sbx.log.SbxLog
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._

class LogRoutesTest extends FlatSpec with Matchers with RoutesTestBase with BeforeAndAfterAll {

  def dbUrl() = "jdbc:h2:mem:logroutestest;DB_CLOSE_DELAY=-1"

  override def beforeAll() {
    super.beforeAll()
    SbxLog.info("Category1", "Message1")
    SbxLog.info("Category1", "Message2")
    SbxLog.warn("Category1", "Message3")
    SbxLog.warn("Category2", "Message4")
    SbxLog.default("Category2", "Message5")
    SbxLog.error("Category2", "Message6")
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