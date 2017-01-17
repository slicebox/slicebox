package se.nimsa.sbx.app.routing

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.Route
import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.importing.ImportProtocol._
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.user.UserProtocol.UserRole
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.Future

class ImportRoutesTest extends {
  val dbConfig = TestUtil.createTestDb("importroutestest")
  val storage = new RuntimeStorage
} with FlatSpecLike with Matchers with RoutesTestBase {

  override def afterEach() =
    await(Future.sequence(Seq(
      metaDataDao.clear(),
      importDao.clear()
    )))

  val importSession = ImportSession(id = -1, name = "importSessionName", userId = -1, user = "", filesImported = -1, filesAdded = -1, filesRejected = -1, created = -1, lastUpdated = -1)

  it should "return 200 OK and a list of import sessions when listing sessions" in {
    PostAsUser("/api/import/sessions", importSession) ~> routes ~> check {
      responseAs[ImportSession]
    }
    GetAsUser("/api/import/sessions") ~> routes ~> check {
      status should be(OK)
      val importSessionSeq = responseAs[Seq[ImportSession]]
      importSessionSeq should have length 1
      importSessionSeq.head.name should be(importSession.name)
    }
  }

  it should "return 201 Created when adding a new import session" in {
    PostAsUser("/api/import/sessions", importSession) ~> routes ~> check {
      status should be(Created)
      val createdSession = responseAs[ImportSession]
      createdSession.id should not be -1
      createdSession.name should be(importSession.name)
    }
  }

  it should "return 204 NoContent when deleting an import session" in {
    val addedSession = PostAsUser("/api/import/sessions", importSession) ~> routes ~> check {
      responseAs[ImportSession]
    }
    DeleteAsUser(s"/api/import/sessions/${addedSession.id}") ~> routes ~> check {
      status should be(NoContent)
    }
    await(importDao.getImportSessions(0, 10)) shouldBe empty
  }

  it should "return 204 NoContent when deleting an import session that does not exist" in {
    DeleteAsUser("/api/import/sessions/123456789") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return 201 Created when adding an import session with a name that already exists" in {
    PostAsUser("/api/import/sessions", importSession) ~> routes ~> check {
      responseAs[ImportSession]
    }
    PostAsUser("/api/import/sessions", importSession) ~> routes ~> check {
      status shouldBe Created
    }
  }

  it should "return 400 BadRequest when adding an import session with a name that already exists but aa another user" in {
    addUser("otheruser", "otherpassword", UserRole.USER)
    PostAsUser("/api/import/sessions", importSession) ~> routes ~> check {
      responseAs[ImportSession]
    }
    PostWithHeaders("/api/import/sessions", importSession).addCredentials(BasicHttpCredentials("otheruser", "otherpassword")) ~> routes ~> check {
      status shouldBe BadRequest
    }
  }

  it should "return 200 OK and the requested import session when fetching a specific import session" in {
    val addedSession = PostAsUser("/api/import/sessions", importSession) ~> routes ~> check {
      responseAs[ImportSession]
    }
    GetAsUser(s"/api/import/sessions/${addedSession.id}") ~> routes ~> check {
      status should be(OK)
    }
  }

  it should "return 404 NotFound when fetching an import session that does not exist" in {
    GetAsUser("/api/import/sessions/666") ~> Route.seal(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "return 201 Created and update counters when adding an image to an import session" in {
    val addedSession = PostAsUser("/api/import/sessions", importSession) ~> routes ~> check {
      responseAs[ImportSession]
    }

    PostAsUser(s"/api/import/sessions/${addedSession.id}/images", TestUtil.testImageFormData) ~> routes ~> check {
      status should be(Created)
      val image = responseAs[Image]
      image.id should not be -1
    }

    val updatedSession = GetAsUser(s"/api/import/sessions/${addedSession.id}") ~> routes ~> check {
      responseAs[ImportSession]
    }
    updatedSession.filesImported should be(1)
    updatedSession.filesAdded should be(1)
    updatedSession.filesRejected should be(0)
  }

  it should "return 200 OK and update counters when adding an already added image to an import session" in {
    val addedSession = PostAsUser("/api/import/sessions", importSession) ~> routes ~> check {
      responseAs[ImportSession]
    }

    PostAsUser(s"/api/import/sessions/${addedSession.id}/images", TestUtil.testImageFormData) ~> routes ~> check {
      status should be(Created)
    }

    PostAsUser(s"/api/import/sessions/${addedSession.id}/images", TestUtil.testImageFormData) ~> routes ~> check {
      status should be(OK)
      val image = responseAs[Image]
      image.id should not be -1
    }

    val updatedSession2 = GetAsUser(s"/api/import/sessions/${addedSession.id}") ~> routes ~> check {
      responseAs[ImportSession]
    }
    updatedSession2.filesImported should be(2)
    updatedSession2.filesAdded should be(1)
    updatedSession2.filesRejected should be(0)
  }

  it should "return 200 OK and the list of images associated with an import session" in {
    val addedSession = PostAsUser("/api/import/sessions", importSession) ~> routes ~> check {
      responseAs[ImportSession]
    }

    val addedImage =
      PostAsUser(s"/api/import/sessions/${addedSession.id}/images", TestUtil.testImageFormData) ~> routes ~> check {
        responseAs[Image]
      }

    GetAsUser(s"/api/import/sessions/${addedSession.id}/images") ~> routes ~> check {
      status shouldBe OK
      val images = responseAs[Seq[Image]]
      images should have length 1
      images.head shouldBe addedImage
    }
  }

  it should "return 400 Bad Request and update counters when adding a jpg image to an import session" in {
    val addedSession = PostAsUser("/api/import/sessions", importSession) ~> routes ~> check {
      responseAs[ImportSession]
    }
    val file = TestUtil.jpegFile
    val mfd = TestUtil.createMultipartFormWithFile(file)
    PostAsUser(s"/api/import/sessions/${addedSession.id}/images", mfd) ~> routes ~> check {
      status should be(BadRequest)
    }

    val updatedSession = GetAsUser(s"/api/import/sessions/${addedSession.id}") ~> routes ~> check {
      responseAs[ImportSession]
    }
    updatedSession.filesImported should be(0)
    updatedSession.filesAdded should be(0)
    updatedSession.filesRejected should be(1)
  }
}
