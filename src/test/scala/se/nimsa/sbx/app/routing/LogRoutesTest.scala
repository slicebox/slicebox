package se.nimsa.sbx.app.routing

import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.nimsa.sbx.log.LogProtocol.LogEntry
import se.nimsa.sbx.log.SbxLog
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import scala.slick.driver.H2Driver
import se.nimsa.sbx.log.LogDAO

class LogRoutesTest extends FlatSpec with Matchers with RoutesTestBase with BeforeAndAfterAll {

  def dbUrl = "jdbc:h2:mem:logroutestest;DB_CLOSE_DELAY=-1"
  
  val logDao = new LogDAO(H2Driver)

  override def beforeEach() {
    db.withSession { implicit session =>
      logDao.clear
    }
    
    SbxLog.info("Category1", "Message1")
    SbxLog.info("Category1", "Message2")
    SbxLog.warn("Category1", "Message3")
    SbxLog.warn("Category2", "Message4")
    SbxLog.default("Category2", "Message5")
    SbxLog.error("Category2", "Message6")
  }
  
  "Log routes" should "support listing log messages" in {
    GetAsUser("/api/log?startindex=0&count=1000") ~> routes ~> check {
      status shouldBe OK
      responseAs[List[LogEntry]] should have length 6
    }
  }
  
  it should "support listing filtered log messages" in {
    GetAsUser("/api/log?startindex=0&count=1000&subject=Category1&type=INFO") ~> routes ~> check {
      status should be(OK)
      responseAs[List[LogEntry]].size should be(2)
    }    
  }
  
  it should "support removing log messages" in {
    val logEntries = db.withSession { implicit session =>
      logDao.listLogEntries(0, 2)
    }
    
    DeleteAsUser(s"/api/log/${logEntries(0).id}") ~> routes ~> check {
      status should be (NoContent)
    }
    
    DeleteAsUser(s"/api/log/${logEntries(1).id}") ~> routes ~> check {
      status should be (NoContent)
    }
    
    GetAsUser("/api/log?startindex=0&count=1000") ~> routes ~> check {
      status should be(OK)
      responseAs[List[LogEntry]].size should be(4)
    }    
  }
}