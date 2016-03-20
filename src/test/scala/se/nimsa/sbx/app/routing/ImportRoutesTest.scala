package se.nimsa.sbx.app.routing

import java.io.File
import scala.slick.driver.H2Driver
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.nimsa.sbx.anonymization.AnonymizationDAO
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.dicom.ImageAttribute
import se.nimsa.sbx.util.TestUtil
import spray.http.BodyPart
import spray.http.ContentTypes
import spray.http.HttpData
import spray.http.MultipartFormData
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling.BasicUnmarshallers.ByteArrayUnmarshaller
import se.nimsa.sbx.metadata.MetaDataDAO
import se.nimsa.sbx.importing.ImportProtocol._
import se.nimsa.sbx.importing.ImportDAO

class ImportRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  override def dbUrl() = "jdbc:h2:mem:importroutestest;DB_CLOSE_DELAY=-1"

  val importDao = new ImportDAO(H2Driver)

  override def afterEach() {
    db.withSession { implicit session =>
      importDao.clear
    }
  }

  it should "return 200 OK and a list of import sessions when listing sessions" in {
    PostAsUser("/api/importing/sessions") ~> routes ~> check { responseAs[ImportSession] }
    GetAsUser("/api/importing/sessions") ~> routes ~> check {
      status should be(OK)
      responseAs[Seq[ImportSession]] should not be empty
    }
  }

  it should "return 201 Created when adding a new import session" in {
    PostAsUser("/api/importing/sessions") ~> routes ~> check {
      status should be(Created)
      val createdSession = responseAs[ImportSession]
      createdSession.id should not be -1
    }
  }

  it should "return 204 NoContent when deleting an import session" in {
    val addedSession = PostAsUser("/api/importing/sessions") ~> routes ~> check { responseAs[ImportSession] }
    DeleteAsUser(s"/api/importing/sessions/${addedSession.id}") ~> routes ~> check {
      status should be(NoContent)
    }
    db.withSession { implicit session =>
      importDao.getImportSessions shouldBe empty
    }
  }

  it should "return 204 NoContent when deleting an import session that does not exist" in {
    DeleteAsUser("/api/importing/sessions/123456789") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return 200 OK and the requested import session when fetching a specific import session" in {
    val addedSession = PostAsUser("/api/importing/sessions") ~> routes ~> check { responseAs[ImportSession] }
    GetAsUser(s"/api/importing/sessions/${addedSession.id}") ~> routes ~> check {
      status should be(OK)
    }
  }
  
  it should "return 404 NotFound when fetching an import session that does not exist" in {
    GetAsUser("/api/importing/sessions/666") ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "return 201 Created when adding an image to an import session" in {
    val addedSession = PostAsUser("/api/importing/sessions") ~> routes ~> check { responseAs[ImportSession] }
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser(s"/api/importing/sessions/${addedSession.id}/images", mfd) ~> routes ~> check {
      status should be(Created)
      val image = responseAs[Image]
      image.id should not be -1
    }    
  }
  
}
