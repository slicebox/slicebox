package se.nimsa.sbx.app.routing

import org.dcm4che3.data.{Tag, VR}
import org.scalatest.{FlatSpec, Matchers}
import se.nimsa.sbx.anonymization.AnonymizationDAO
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.metadata.MetaDataDAO
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.util.TestUtil
import spray.http.{BodyPart, HttpData, MultipartFormData}
import spray.http.StatusCodes._
import spray.httpx.unmarshalling.BasicUnmarshallers.ByteArrayUnmarshaller
import spray.httpx.SprayJsonSupport._

import scala.slick.driver.H2Driver

class AnonymizationRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl = "jdbc:h2:mem:anonymizationroutestest;DB_CLOSE_DELAY=-1"

  val dao = new AnonymizationDAO(H2Driver)
  val metaDataDao = new MetaDataDAO(H2Driver)

  override def afterEach() {
    db.withSession { implicit session =>
      dao.clear
      metaDataDao.clear
    }
  }

  "Anonymization routes" should "return 200 OK and anonymize the data by removing the old data and inserting new anonymized data upon manual anonymization" in {
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    val image =
      PostAsUser("/api/images", mfd) ~> routes ~> check {
        status shouldBe Created
        responseAs[Image]
      }

    val flatSeries = GetAsUser(s"/api/metadata/flatseries/${image.seriesId}") ~> routes ~> check {
      status should be(OK)
      responseAs[FlatSeries]
    }
    val anonImage =
      PutAsUser(s"/api/images/${flatSeries.patient.id}/anonymize", Seq.empty[TagValue]) ~> routes ~> check {
        status should be(OK)
        responseAs[Image]
      }

    image.id should not be anonImage.id

    GetAsUser("/api/metadata/patients/1") ~> routes ~> check {
      status should be(NotFound)
    }
    GetAsUser("/api/metadata/patients/2") ~> routes ~> check {
      status should be(OK)
      val anonPatient = responseAs[Patient]
      anonPatient.patientName should not be flatSeries.patient.patientName
      anonPatient.patientID should not be flatSeries.patient.patientID
      anonPatient.patientSex should be(flatSeries.patient.patientSex)
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

  it should "return 200 OK and the image IDs of the new anonymized images when bulk anonymizing a sequence of images" in {
    val ds1 = TestUtil.testImageDataset(true)
    val ds2 = TestUtil.testImageDataset(true)
    ds2.setString(Tag.PatientName, VR.PN, "John^Doe")

    val image1 =
      PostAsUser("/api/images", HttpData(DicomUtil.toByteArray(ds1))) ~> routes ~> check {
        status should be(Created)
        responseAs[Image]
      }

    val image2 =
      PostAsUser("/api/images", HttpData(DicomUtil.toByteArray(ds2))) ~> routes ~> check {
        status should be(Created)
        responseAs[Image]
      }

    GetAsUser(s"/api/metadata/flatseries/${image1.seriesId}") ~> routes ~> check {
      status should be(OK)
      responseAs[FlatSeries]
    }

    GetAsUser(s"/api/metadata/flatseries/${image2.seriesId}") ~> routes ~> check {
      status should be(OK)
      responseAs[FlatSeries]
    }

    val anonImages =
      PostAsUser("/api/anonymization/anonymize", Seq(ImageTagValues(image1.id, Seq.empty), ImageTagValues(image2.id, Seq.empty))) ~> routes ~> check {
        status should be(OK)
        responseAs[Seq[Image]]
      }

    anonImages should have length 2

    val anonFlatSeries1 =
      GetAsUser(s"/api/metadata/flatseries/${anonImages.head.seriesId}") ~> routes ~> check {
        status should be(OK)
        responseAs[FlatSeries]
      }

    val anonFlatSeries2 =
      GetAsUser(s"/api/metadata/flatseries/${anonImages(1).seriesId}") ~> routes ~> check {
        status should be(OK)
        responseAs[FlatSeries]
      }

    anonFlatSeries1.patient.patientName.value.startsWith("Anonymous") shouldBe true
    anonFlatSeries2.patient.patientName.value.startsWith("Anonymous") shouldBe true
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

  it should "return 200 OK and the requested anonymization key" in {
    db.withSession { implicit session =>
      val dataset = TestUtil.createDataset()
      val key1 = TestUtil.createAnonymizationKey(dataset)
      val insertedKey1 = dao.insertAnonymizationKey(key1)
      GetAsUser(s"/api/anonymization/keys/${insertedKey1.id}") ~> routes ~> check {
        status shouldBe OK
        responseAs[AnonymizationKey] shouldBe insertedKey1
      }
    }
  }

  it should "return 404 NotFound when the requested anonymization key does not exist" in {
    GetAsUser("/api/anonymization/keys/666") ~> sealRoute(routes) ~> check {
      status shouldBe NotFound
    }
  }

  it should "provide a list of sorted anonymization keys supporting startindex and count" in {
    db.withSession { implicit session =>
      val dataset = TestUtil.createDataset(patientName = "B")
      val key1 = TestUtil.createAnonymizationKey(dataset, anonPatientName = "anon B")
      val key2 = key1.copy(patientName = "A", anonPatientName = "anon A")
      dao.insertAnonymizationKey(key1)
      val insertedKey2 = dao.insertAnonymizationKey(key2)
      GetAsUser("/api/anonymization/keys?startindex=0&count=1&orderby=patientName&orderascending=true") ~> routes ~> check {
        status should be(OK)
        val keys = responseAs[List[AnonymizationKey]]
        keys.length should be(1)
        keys.head should be(insertedKey2)
      }
    }
  }

  it should "return 400 Bad Request when sorting anonymization keys by a non-existing property" in {
    GetAsUser("/api/anonymization/keys?orderby=xyz") ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return 200 OK and a list of images corresponding to the anonymization key with the supplied ID" in {
    db.withSession { implicit session =>
      val (dbPatient1, (_, _), (_, _, _, _), (dbImage1, dbImage2, _, _, _, _, _, _)) = TestUtil.insertMetaData(metaDataDao)
      val key1 = AnonymizationKey(-1, 1234, dbPatient1.patientName.value, "anonPN", dbPatient1.patientID.value, "anonPID", "", "", "", "", "", "", "", "", "", "", "", "")
      val insertedKey1 = dao.insertAnonymizationKey(key1)
      val akImage1 = AnonymizationKeyImage(-1, insertedKey1.id, dbImage1.id)
      val akImage2 = AnonymizationKeyImage(-1, insertedKey1.id, dbImage2.id)
      dao.insertAnonymizationKeyImage(akImage1)
      dao.insertAnonymizationKeyImage(akImage2)
      val akImages =
        GetAsUser(s"/api/anonymization/keys/${insertedKey1.id}/images") ~> routes ~> check {
          status shouldBe OK
          responseAs[Seq[Image]]
        }
      akImages should have length 2
      akImages shouldBe Seq(dbImage1, dbImage2)
    }
  }

  it should "return 200 OK and a list of anonymization keys when querying" in {
    db.withSession { implicit session =>
      val (dbPatient1, (_, _), (_, _, _, _), (_, _, _, _, _, _, _, _)) = TestUtil.insertMetaData(metaDataDao)
      val key1 = AnonymizationKey(-1, 1234, dbPatient1.patientName.value, "anonPN", dbPatient1.patientID.value, "anonPID", "", "", "", "", "", "", "", "", "", "", "", "")
      val insertedKey1 = dao.insertAnonymizationKey(key1)

      val query = AnonymizationKeyQuery(0, 10, None, Seq(QueryProperty("anonPatientName", QueryOperator.EQUALS, insertedKey1.anonPatientName)))
      val keys =
        PostAsUser("/api/anonymization/keys/query", query) ~> routes ~> check {
          status shouldBe OK
          responseAs[List[AnonymizationKey]]
        }

      keys should have length 1
      keys.head shouldBe insertedKey1
    }
  }

  it should "remove related anonymization image record when an image is deleted" in {
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    val image =
      PostAsUser("/api/images", mfd) ~> routes ~> check {
        status shouldBe Created
        responseAs[Image]
      }

    val key =
      db.withSession { implicit session =>
        val key = dao.insertAnonymizationKey(AnonymizationKey(-1, 1234, "pn", "anonPN", "pid", "anonPID", "", "", "", "", "", "", "", "", "", "", "", ""))
        dao.insertAnonymizationKeyImage(AnonymizationKeyImage(-1, key.id, image.id))
        key
      }

    GetAsUser(s"/api/anonymization/keys/${key.id}/images") ~> routes ~> check {
      status shouldBe OK
      responseAs[List[Image]] should have length 1
    }

    DeleteAsUser(s"/api/images/${image.id}") ~> routes ~> check {
      status shouldBe NoContent
    }

    Thread.sleep(1000) // wait for ImageDeleted event to reach AnonymizationServiceActor

    GetAsUser(s"/api/anonymization/keys/${key.id}/images") ~> routes ~> check {
      status shouldBe OK
      responseAs[List[Image]] shouldBe empty
    }
  }

}