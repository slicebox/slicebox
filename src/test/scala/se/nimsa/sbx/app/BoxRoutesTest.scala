package se.nimsa.sbx.app

import java.nio.file.Paths
import java.util.UUID
import spray.http.BodyPart
import spray.http.MultipartFormData
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.httpx.marshalling.marshal
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.box.BoxDAO
import java.io.File
import se.nimsa.sbx.dicom.DicomUtil
import spray.http.ContentTypes
import spray.httpx.unmarshalling.BasicUnmarshallers.ByteArrayUnmarshaller
import spray.http.HttpData
import scala.math.abs
import spray.http.HttpEntity
import spray.http.FormFile
import spray.http.HttpEntity.NonEmpty
import se.nimsa.sbx.dicom.DicomHierarchy.Patient
import se.nimsa.sbx.dicom.DicomProperty._
import scala.slick.driver.H2Driver
import java.util.Date
import se.nimsa.sbx.anonymization.AnonymizationProtocol.TagValue
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey
import se.nimsa.sbx.util.TestUtil

class BoxRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:boxroutestest;DB_CLOSE_DELAY=-1"

  val boxDao = new BoxDAO(H2Driver)

  override def afterEach() {
    db.withSession { implicit session =>
      boxDao.clear
    }
  }

  def addPollBox(name: String) =
    PostAsAdmin("/api/boxes/createconnection", RemoteBoxName(name)) ~> routes ~> check {
      status should be(Created)
      val response = responseAs[Box]
      response
    }

  def addPushBox(name: String) =
    PostAsAdmin("/api/boxes/connect", RemoteBox(name, "http://some.url/api/box/" + UUID.randomUUID())) ~> routes ~> check {
      status should be(Created)
      val box = responseAs[Box]
      box.sendMethod should be(BoxSendMethod.PUSH)
      box.name should be(name)
      box
    }

  "Box routes" should "return a success message when asked to generate a new base url" in {
    addPollBox("hosp")
  }

  it should "return a bad request message when asking to generate a new base url with a malformed request body" in {
    PostAsAdmin("/api/boxes/createconnection", RemoteBox("name", "url")) ~> sealRoute(routes) ~> check {
      status should be(BadRequest)
    }
  }

  it should "return a bad request message when adding two boxes with the same name" in {
    addPollBox("hosp")
    PostAsAdmin("/api/boxes/createconnection", RemoteBoxName("hosp")) ~> sealRoute(routes) ~> check {
      status should be(BadRequest)
    }
  }
  it should "return a success message when asked to add a remote box" in {
    addPushBox("uni")
  }

  it should "return a bad request message when asked to add a remote box with a malformed base url" in {
    PostAsAdmin("/api/boxes/connect", RemoteBox("uni2", "")) ~> sealRoute(routes) ~> check {
      status should be(BadRequest)
    }
    PostAsAdmin("/api/boxes/connect", RemoteBox("uni2", "malformed/url")) ~> sealRoute(routes) ~> check {
      status should be(BadRequest)
    }
  }

  it should "return a list of two boxes when listing boxes" in {
    val box1 = addPollBox("hosp")
    val box2 = addPushBox("uni")
    GetAsUser("/api/boxes") ~> routes ~> check {
      val boxes = responseAs[List[Box]]
      boxes.size should be(2)
    }
  }

  it should "return a no content message when asked to send images" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/send", Seq(ImageTagValues(1, Seq.empty))) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a no content message when asked to send images with empty images list" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/send", Seq.empty[ImageTagValues]) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a not found message when asked to send images with unknown box id" in {
    PostAsAdmin("/api/boxes/999/send", Seq(ImageTagValues(1, Seq.empty))) ~> sealRoute(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "support removing a box" in {
    val box1 = addPollBox("hosp1")
    val box2 = addPollBox("hosp2")
    DeleteAsAdmin("/api/boxes/" + box1.id) ~> routes ~> check {
      status should be(NoContent)
    }
    DeleteAsAdmin("/api/boxes/" + box2.id) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a no content message when asked to remove a box that does not exist" in {
    DeleteAsAdmin("/api/boxes/999") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "be able to receive a pushed image" in {

    // first, add a box on the poll (university) side
    val uniBox =
      PostAsAdmin("/api/boxes/createconnection", RemoteBoxName("hosp")) ~> routes ~> check {
        status should be(Created)
        responseAs[Box]
      }

    // then, push an image from the hospital to the uni box we just set up
    val bytes = TestUtil.testImageByteArray

    val testTransactionId = abs(UUID.randomUUID().getMostSignificantBits())
    val sequenceNumber = 1L
    val totalImageCount = 1L

    Post(s"/api/box/${uniBox.token}/image?transactionid=$testTransactionId&sequencenumber=$sequenceNumber&totalimagecount=$totalImageCount", HttpData(bytes)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return unauthorized when polling outbox with unvalid token" in {
    Get(s"/api/box/abc/outbox/poll") ~> sealRoute(routes) ~> check {
      status should be(Unauthorized)
    }
  }

  it should "return not found when polling empty outbox" in {
    // first, add a box on the poll (university) side
    val uniBox =
      PostAsAdmin("/api/boxes/createconnection", RemoteBoxName("hosp2")) ~> routes ~> check {
        status should be(Created)
        responseAs[Box]
      }

    Get(s"/api/box/${uniBox.token}/outbox/poll") ~> sealRoute(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "return OutboxEntry when polling non empty outbox" in {
    // first, add a box on the poll (university) side
    val uniBox = addPollBox("hosp3")

    // send image which adds outbox entry
    PostAsUser(s"/api/boxes/${uniBox.id}/send", Seq(ImageTagValues(1, Seq.empty))) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outbox
    Get(s"/api/box/${uniBox.token}/outbox/poll") ~> routes ~> check {
      status should be(OK)

      val outboxEntry = responseAs[OutboxEntry]

      outboxEntry.remoteBoxId should be(uniBox.id)
      outboxEntry.sequenceNumber should be(1)
      outboxEntry.totalImageCount should be(1)
      outboxEntry.failed should be(false)
      outboxEntry.transactionId should not be (0)
    }
  }

  it should "return a non-empty result when listing outbox entries for sent images" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/send", Seq(ImageTagValues(1, Seq.empty))) ~> routes ~> check {
      status should be(NoContent)
    }
    GetAsUser("/api/outbox") ~> routes ~> check {
      status should be(OK)
      responseAs[List[OutboxEntryInfo]].length should be > 0
    }
  }

  it should "return an image file when requesting outbox entry" in {
    // add image (image will get id 1)
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd)

    // first, add a box on the poll (university) side
    val uniBox = addPollBox("hosp4")

    // send image which adds outbox entry
    PostAsUser(s"/api/boxes/${uniBox.id}/send", Seq(ImageTagValues(1, Seq.empty))) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outbox
    val outboxEntry =
      Get(s"/api/box/${uniBox.token}/outbox/poll") ~> routes ~> check {
        status should be(OK)
        responseAs[OutboxEntry]
      }

    // get image
    Get(s"/api/box/${uniBox.token}/outbox?transactionid=${outboxEntry.transactionId}&sequencenumber=1") ~> routes ~> check {
      status should be(OK)

      contentType should be(ContentTypes.`application/octet-stream`)

      val dataset = DicomUtil.loadDataset(responseAs[Array[Byte]], true)
      dataset should not be (null)
    }
  }

  it should "remove outbox entry when done is received" in {
    // add image (image will get id 1)
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd)

    // first, add a box on the poll (university) side
    val uniBox = addPollBox("hosp5")

    // send image which adds outbox entry
    PostAsUser(s"/api/boxes/${uniBox.id}/send", Seq(ImageTagValues(1, Seq.empty))) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outbox
    val outboxEntry =
      Get(s"/api/box/${uniBox.token}/outbox/poll") ~> routes ~> check {
        status should be(OK)
        responseAs[OutboxEntry]
      }

    // send done
    Post(s"/api/box/${uniBox.token}/outbox/done", outboxEntry) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outbox to check that outbox is empty
    Get(s"/api/box/${uniBox.token}/outbox/poll") ~> sealRoute(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "correctly map dicom attributes according to supplied mapping when sending images" in {
    // add image (image will get id 1)
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd)

    // first, add a box on the poll (university) side
    val uniBox = addPollBox("hosp6")

    // create attribute mappings
    val patient = GetAsUser(s"/api/metadata/patients") ~> routes ~> check {
      responseAs[List[Patient]].apply(0)
    }

    val imageTagValues = ImageTagValues(1, Seq(
      TagValue(PatientName.dicomTag, "TEST NAME"),
      TagValue(PatientID.dicomTag, "TEST ID"),
      TagValue(PatientBirthDate.dicomTag, "19601010")))

    // send image which adds outbox entry
    PostAsUser(s"/api/boxes/${uniBox.id}/send", Seq(imageTagValues)) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outbox
    val outboxEntry =
      Get(s"/api/box/${uniBox.token}/outbox/poll") ~> routes ~> check {
        status should be(OK)
        responseAs[OutboxEntry]
      }

    // get image
    val transactionId = outboxEntry.transactionId
    val sequenceNumber = outboxEntry.sequenceNumber
    val byteArray = Get(s"/api/box/${uniBox.token}/outbox?transactionid=$transactionId&sequencenumber=$sequenceNumber") ~> routes ~> check {
      status should be(OK)
      responseAs[Array[Byte]]
    }
    val dataset = DicomUtil.loadDataset(byteArray, false)
    dataset.getString(PatientName.dicomTag) should be("TEST NAME") // mapped
    dataset.getString(PatientID.dicomTag) should be("TEST ID") // mapped
    dataset.getString(PatientBirthDate.dicomTag) should be("19601010") // mapped
    dataset.getString(PatientSex.dicomTag) should be(patient.patientSex.value) // not mapped

    // send done
    Post(s"/api/box/${uniBox.token}/outbox/done", outboxEntry) ~> routes ~> check {
      status should be(NoContent)
    }
  }

}