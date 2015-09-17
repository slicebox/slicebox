package se.nimsa.sbx.app.routing

import java.nio.file.Files
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.nimsa.sbx.scp.ScpProtocol._
import spray.httpx.SprayJsonSupport._
import spray.http.StatusCodes._

class ScpRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:scproutestest;DB_CLOSE_DELAY=-1"
  
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
    DeleteAsAdmin("/api/scps/1") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return 400 Bad Request when adding an SCP with an invalid port number" in {
    PostAsAdmin("/api/scps", ScpData(-1, "TestName", "TestAeTitle", -1)) ~> routes ~> check {
      status should be (BadRequest)
    }    
    PostAsAdmin("/api/scps", ScpData(-1, "TestName", "TestAeTitle", 66000)) ~> routes ~> check {
      status should be (BadRequest)
    }    
  }
  
  it should "return 400 Bad Request when adding an SCP with an invalid AE title" in {
    PostAsAdmin("/api/scps", ScpData(-1, "TestName", "ABCDEFGHIJKLMNOPQ", 123)) ~> routes ~> check {
      status should be (BadRequest)
    }
  }

  it should "return 201 Created when adding an SCP with an AE title over 16 characters long but less than 17 characters after trimming" in {
    PostAsAdmin("/api/scps", ScpData(-1, "TestName", " ABCDEFGHIJKLMNOP ", 123)) ~> routes ~> check {
      status should be (Created)
    }
  }
  
  
}