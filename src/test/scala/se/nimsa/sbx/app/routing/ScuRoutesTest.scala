package se.nimsa.sbx.app.routing

import akka.http.scaladsl.model.StatusCodes._
import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.scp.ScpProtocol.ScpData
import se.nimsa.sbx.scu.ScuProtocol._
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

class ScuRoutesTest extends {
  val dbConfig = TestUtil.createTestDb("scuroutestest")
  val storage = new RuntimeStorage
} with FlatSpecLike with Matchers with RoutesTestBase {

  "SCU routes" should "return a success message when asked to setup a new SCP and SCU" in {
    PostAsAdmin("/api/scps", ScpData(-1, "ScpName", "TestAeTitle", 12347)) ~> routes ~> check {
      status should be(Created)
    }
    PostAsAdmin("/api/scus", ScuData(-1, "TestName", "TestAeTitle", "127.0.0.1", 12347)) ~> routes ~> check {
      status should be(Created)
      val scuData = responseAs[ScuData]
      scuData.name should be("TestName")
    }
  }

  it should "return 200 OK when listing SCUs" in {
    GetAsUser("/api/scus") ~> routes ~> check {
      status should be(OK)
      responseAs[List[ScuData]].length should be(1)
    }
  }

  it should "return 404 NotFound when asking to send an image using an SCP that does not exist" in {
    PostAsUser("/api/scus/666/send", Array(1)) ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "return 502 BadGateway when sending an image to an SCP that is unavailable" in {
    val image = PostAsUser("/api/images", TestUtil.testImageFormData) ~> routes ~> check {
      status should be(Created)
      responseAs[Image]
    }
    val scu = PostAsAdmin("/api/scus", ScuData(-1, "TestName2", "TestAeTitle2", "127.0.0.1", 12349)) ~> routes ~> check {
      status should be(Created)
      responseAs[ScuData]
    }
    PostAsUser(s"/api/scus/${scu.id}/send", Array(image.id)) ~> routes ~> check {
      status should be(BadGateway)
    }
  }

  it should "return 204 NoContent when asking to send an image using an SCU" in {
    val scu = await(scuDao.listScuDatas(0, 1)).head
    val image = await(metaDataDao.images).head
    PostAsUser(s"/api/scus/${scu.id}/send", Array(image.id)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "be possible to remove the SCU again" in {
    val scu = await(scuDao.listScuDatas(0, 1)).head
    val scp = await(scpDao.listScpDatas(0, 1)).head
    DeleteAsAdmin(s"/api/scus/${scu.id}") ~> routes ~> check {
      status should be(NoContent)
    }
    DeleteAsAdmin(s"/api/scps/${scp.id}") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return 400 Bad Request when adding an SCU with an invalid port number" in {
    PostAsAdmin("/api/scus", ScuData(-1, "TestName", "TestAeTitle", "123.456.789.1", -1)) ~> routes ~> check {
      status should be(BadRequest)
    }
    PostAsAdmin("/api/scus", ScuData(-1, "TestName", "TestAeTitle", "123.456.789.1", 66000)) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return 400 Bad Request when adding an SCU with an invalid AE title" in {
    PostAsAdmin("/api/scus", ScuData(-1, "TestName", "ABCDEFGHIJKLMNOPQ", "123.456.789.1", 12321)) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return 201 Created when adding an SCU with an AE title over 16 characters long but less than 17 characters after trimming" in {
    PostAsAdmin("/api/scus", ScuData(-1, "TestName", " ABCDEFGHIJKLMNOP ", "123.456.789.1", 12323)) ~> routes ~> check {
      status should be(Created)
    }
  }

}