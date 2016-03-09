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

class ImportRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  override def dbUrl() = "jdbc:h2:mem:importroutestest;DB_CLOSE_DELAY=-1"

  it should "return 200 OK and a list of import sessions ..." in {
    GetAsUser("/api/importing/sessions") ~> routes ~> check {
      status should be(OK)
    }
  }

  it should "return 201 Created when calling post" in {
    PostAsUser("/api/importing/sessions") ~> routes ~> check {
      status should be(Created)
    }
  }

  it should "return 204 NoContent when calling delete" in {
    DeleteAsUser("/api/importing/sessions/123456789") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return 200 OK when calling get with id" in {
    GetAsUser("/api/importing/sessions/123456789") ~> routes ~> check {
      status should be(OK)
    }
  }
}
