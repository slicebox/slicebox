package se.nimsa.sbx.app.routing

import java.nio.file.Files
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.nimsa.sbx.scu.ScuProtocol._
import spray.httpx.SprayJsonSupport._
import spray.http.StatusCodes._
import se.nimsa.sbx.util.TestUtil
import spray.http.MultipartFormData
import spray.http.BodyPart
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.scp.ScpProtocol.ScpData
import se.nimsa.sbx.scp.ScpDAO
import se.nimsa.sbx.scu.ScuDAO
import se.nimsa.sbx.storage.MetaDataDAO
import scala.slick.driver.H2Driver

class ScuRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:scproutestest;DB_CLOSE_DELAY=-1"

  val scpDao = new ScpDAO(H2Driver)
  val scuDao = new ScuDAO(H2Driver)
  val metaDataDao = new MetaDataDAO(H2Driver)

  "SCU routes" should "return a success message when asked to setup a new SCP" in {
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

  it should "return 404 NotFound when asking to send a series using an SCP that does not exist" in {
    PostAsUser("/api/scus/666/sendseries/1") ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "return 404 NotFound when asking to send a series that does not exist" in {
    PostAsUser("/api/scus/1/sendseries/666") ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "return 404 NotFound when sending a series to an SCP that is unavailable" in {
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    val image = PostAsUser("/api/images", mfd) ~> routes ~> check {
      status should be(Created)
      responseAs[Image]
    }
    val scu = PostAsAdmin("/api/scus", ScuData(-1, "TestName2", "TestAeTitle2", "127.0.0.1", 12349)) ~> routes ~> check {
      status should be(Created)
      responseAs[ScuData]
    }
    PostAsUser(s"/api/scus/${scu.id}/sendseries/${image.id}") ~> routes ~> check {
      status should be(BadGateway)
    }
  }

  it should "return 204 NoContent when asking to send a series using an SCU" in {
    val scu = db.withSession { implicit session =>
      scuDao.allScuDatas.head
    }
    val image = db.withSession { implicit session =>
      metaDataDao.images.head
    }    
    PostAsUser(s"/api/scus/${scu.id}/sendseries/${image.id}") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "be possible to remove the SCU again" in {
    val scu = db.withSession { implicit session =>
      scuDao.allScuDatas.head
    }
    val scp = db.withSession { implicit session =>
      scpDao.allScpDatas.head
    }
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