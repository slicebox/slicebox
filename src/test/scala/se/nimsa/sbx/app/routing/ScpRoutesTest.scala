package se.nimsa.sbx.app.routing

import akka.http.scaladsl.model.StatusCodes._
import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.sbx.scp.ScpDAO
import se.nimsa.sbx.scp.ScpProtocol._
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.TestUtil

class ScpRoutesTest extends {
  val dbProps = TestUtil.createTestDb("scproutestest")
  val storage = new RuntimeStorage
} with FlatSpecLike with Matchers with RoutesTestBase {

  val db = dbProps.db
  val scpDao = new ScpDAO(dbProps.driver)
  
  "SCP routes" should "return a success message when asked to start a new SCP" in {
    PostAsAdmin("/api/scps", ScpData(-1, "TestName", "TestAeTitle", 13579)) ~> routes ~> check {
      status should be (Created)
      val scpData = responseAs[ScpData]
      scpData.name should be("TestName")
    }
  }

  it should "return 200 OK when listing SCPs" in {
    GetAsUser("/api/scps") ~> routes ~> check {
      status should be (OK)
      responseAs[List[ScpData]].length should be (1)
    }    
  }
  it should "be possible to remove the SCP again" in {
    val scp = db.withSession { implicit session =>
      scpDao.listScpDatas(0, 1).head
    }
    DeleteAsAdmin(s"/api/scps/${scp.id}") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return 400 Bad Request when adding an SCP with an invalid port number" in {
    PostAsAdmin("/api/scps", ScpData(-1, "InvalidTestName", "TestAeTitle", -1)) ~> routes ~> check {
      status should be (BadRequest)
    }    
    PostAsAdmin("/api/scps", ScpData(-1, "InvalidTestName", "TestAeTitle", 66000)) ~> routes ~> check {
      status should be (BadRequest)
    }    
  }
  
  it should "return 400 Bad Request when adding an SCP with an invalid AE title" in {
    PostAsAdmin("/api/scps", ScpData(-1, "InvalidTestName", "ABCDEFGHIJKLMNOPQ", 12321)) ~> routes ~> check {
      status should be (BadRequest)
    }
  }

  it should "return 201 Created when adding an SCP with an AE title over 16 characters long but less than 17 characters after trimming" in {
    val scp = PostAsAdmin("/api/scps", ScpData(-1, "SomeOtherTestName", " ABCDEFGHIJKLMNOP ", 12324)) ~> routes ~> check {
      status should be (Created)
      responseAs[ScpData]
    }
    DeleteAsAdmin(s"/api/scps/${scp.id}") ~> routes ~> check {
      status should be(NoContent)
    }
  }
  
  
}