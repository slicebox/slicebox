package se.nimsa.sbx.app.routing

import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.user.UserProtocol.UserRole._
import se.nimsa.sbx.box.BoxProtocol.RemoteBoxConnectionData
import se.nimsa.sbx.util.TestUtil
import se.nimsa.sbx.scu.ScuProtocol.ScuData
import se.nimsa.sbx.scp.ScpProtocol.ScpData
import se.nimsa.sbx.app.GeneralProtocol._
import akka.http.scaladsl.model.StatusCodes._
import se.nimsa.sbx.storage.RuntimeStorage

class GeneralRoutesTest extends {
  val dbProps = TestUtil.createTestDb("generalroutestest")
  val storage = new RuntimeStorage
} with FlatSpecLike with Matchers with RoutesTestBase {

  "General routes" should "return a list of sources of the correct length" in {
    PostAsAdmin("/api/users", ClearTextUser("name", ADMINISTRATOR, "password")) ~> routes ~> check {
      status should be(Created)
    }
    PostAsAdmin("/api/boxes/createconnection", RemoteBoxConnectionData("remote box")) ~> routes ~> check {
      status should be(Created)
    }
    PostAsAdmin("/api/scps", ScpData(-1, "my scp", "MYAETITLE", 5002)) ~> routes ~> check {
      status should be(Created)
    }
    GetAsUser("/api/sources") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Source]].length should be(5) // admin + user users added by system and test class, name user, remote box, scp
    }
  }

  it should "return a list of destinations of the correct length" in {
    PostAsAdmin("/api/scus", ScuData(-1, "my scu", "AETITLE", "123.123.123.1", 5003)) ~> routes ~> check {
      status should be(Created)
    }
    GetAsUser("/api/destinations") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Destination]].length should be(2) // remote box, scu
    }    
  }

  it should "return 200 OK when health status is checked and the service in running" in {
    Get("/api/system/health") ~> routes ~> check {
      status shouldBe OK
    }
  }
}