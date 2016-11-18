package se.nimsa.sbx.app.routing

import akka.http.scaladsl.model.StatusCodes._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import se.nimsa.sbx.log.LogProtocol.LogEntry
import se.nimsa.sbx.log.{LogDAO, SbxLog}
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.TestUtil

class LogRoutesTest extends {
  val dbProps = TestUtil.createTestDb("logroutestest")
  val storage = new RuntimeStorage
} with FlatSpecLike with Matchers with RoutesTestBase with BeforeAndAfterAll {

  val db = dbProps.db
  val logDao = new LogDAO(dbProps.driver)

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
    
    DeleteAsUser(s"/api/log/${logEntries.head.id}") ~> routes ~> check {
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