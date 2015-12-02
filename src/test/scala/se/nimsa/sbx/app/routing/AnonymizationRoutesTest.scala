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

class AnonymizationRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:anonymizationroutestest;DB_CLOSE_DELAY=-1"

  val dao = new AnonymizationDAO(H2Driver)

  override def afterEach() {
    db.withSession { implicit session =>
      dao.clear
    }
  }

  "Anonymization routes" should "return 200 OK and anonymize the data by removing the old data and inserting new anonymized data upon manual anonymization" in {
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd) ~> routes ~> check {
      status should be(Created)
      val image = responseAs[Image]
      image.id should be(1)
    }
    
    val patient = GetAsUser("/api/metadata/patients/1") ~> routes ~> check {
      status should be(OK)
      responseAs[Patient]
    }
    PutAsUser("/api/images/1/anonymize", Seq.empty[TagValue]) ~> routes ~> check {
      status should be(NoContent)
    }
    GetAsUser("/api/metadata/patients/1") ~> routes ~> check {
      status should be(NotFound)
    }
    GetAsUser("/api/metadata/patients/2") ~> routes ~> check {
      status should be(OK)
      val anonPatient = responseAs[Patient]
      anonPatient.patientName should not be (patient.patientName)
      anonPatient.patientID should not be (patient.patientID)
      anonPatient.patientSex should be(patient.patientSex)
    }
    GetAsUser("/api/metadata/studies/1") ~> routes ~> check {
      status should be(NotFound)
    }
    GetAsUser("/api/metadata/studies/2") ~> routes ~> check {
      status should be(OK)
    }
    GetAsUser("/api/metadata/series/1") ~> routes ~> check {
      status should be(NotFound)
    }
    GetAsUser("/api/metadata/series/2") ~> routes ~> check {
      status should be(OK)
    }
    GetAsUser("/api/metadata/images/1") ~> routes ~> check {
      status should be(NotFound)
    }
    GetAsUser("/api/metadata/images/2") ~> routes ~> check {
      status should be(OK)
    }
  }

  it should "return 404 NotFound when manually anonymizing an image that does not exist" in {
    PutAsUser("/api/images/666/anonymize", Seq.empty[TagValue]) ~> routes ~> check {
      status should be(NotFound)
    }
  }

  it should "provide a list of anonymization keys" in {
    db.withSession { implicit session =>
      val dataset = TestUtil.createDataset()
      val key1 = TestUtil.createAnonymizationKey(dataset)
      val key2 = key1.copy(patientName = "pat name 2", anonPatientName = "anon pat name 2")
      val insertedKey1 = dao.insertAnonymizationKey(key1)
      val insertedKey2 = dao.insertAnonymizationKey(key2)
      GetAsUser("/api/anonymization/keys") ~> routes ~> check {
        status should be(OK)
        responseAs[List[AnonymizationKey]] should be(List(insertedKey1, insertedKey2))
      }
    }
  }

  it should "provide a list of sorted anonymization keys supporting startindex and count" in {
    db.withSession { implicit session =>
      val dataset = TestUtil.createDataset(patientName = "B")
      val key1 = TestUtil.createAnonymizationKey(dataset, anonPatientName = "anon B")
      val key2 = key1.copy(patientName = "A", anonPatientName = "anon A")
      val insertedKey1 = dao.insertAnonymizationKey(key1)
      val insertedKey2 = dao.insertAnonymizationKey(key2)
      GetAsUser("/api/anonymization/keys?startindex=0&count=1&orderby=patientName&orderascending=true") ~> routes ~> check {
        status should be(OK)
        val keys = responseAs[List[AnonymizationKey]]
        keys.length should be(1)
        keys(0) should be(insertedKey2)
      }
    }
  }

  it should "return 400 Bad Request when sorting anonymization keys by a non-existing property" in {
    GetAsUser("/api/anonymization/keys?orderby=xyz") ~> routes ~> check {
      status should be(BadRequest)
    }
  }

}