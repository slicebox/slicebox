package se.nimsa.sbx.app.routing

import java.util.UUID

import scala.math.abs
import scala.slick.driver.H2Driver

import org.scalatest.FlatSpec
import org.scalatest.Matchers

import se.nimsa.sbx.anonymization.AnonymizationProtocol.TagValue
import se.nimsa.sbx.box.BoxDAO
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Patient
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomProperty._
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.util.TestUtil
import spray.http.BodyPart
import spray.http.ContentTypes
import spray.http.HttpData
import spray.http.MultipartFormData
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling.BasicUnmarshallers.ByteArrayUnmarshaller
import se.nimsa.sbx.util.CompressionUtil._

class RemoteBoxRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:boxroutestest;DB_CLOSE_DELAY=-1"

  val boxDao = new BoxDAO(H2Driver)

  override def afterEach() {
    db.withSession { implicit session =>
      boxDao.clear
    }
  }

  def addPollBox(name: String) =
    PostAsAdmin("/api/boxes/createconnection", RemoteBoxConnectionData(name)) ~> routes ~> check {
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

  "Remote box routes" should "be able to receive a pushed image" in {

    // first, add a box on the poll (university) side
    val uniBox =
      PostAsAdmin("/api/boxes/createconnection", RemoteBoxConnectionData("hosp")) ~> routes ~> check {
        status should be(Created)
        responseAs[Box]
      }

    // then, push an image from the hospital to the uni box we just set up
    val compressedBytes = compress(TestUtil.testImageByteArray)

    val testTransactionId = abs(UUID.randomUUID().getMostSignificantBits())
    val sequenceNumber = 1L
    val totalImageCount = 1L

    Post(s"/api/box/${uniBox.token}/image?transactionid=$testTransactionId&sequencenumber=$sequenceNumber&totalimagecount=$totalImageCount", HttpData(compressedBytes)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "add a record of the received image connected to the inbox entry" in {

    // first, add a box on the poll (university) side
    val uniBox =
      PostAsAdmin("/api/boxes/createconnection", RemoteBoxConnectionData("hosp")) ~> routes ~> check {
        responseAs[Box]
      }

    // then, push an image from the hospital to the uni box we just set up
    val compressedBytes = compress(TestUtil.testImageByteArray)

    val testTransactionId = abs(UUID.randomUUID().getMostSignificantBits())
    val sequenceNumber = 1L
    val totalImageCount = 1L

    Post(s"/api/box/${uniBox.token}/image?transactionid=$testTransactionId&sequencenumber=$sequenceNumber&totalimagecount=$totalImageCount", HttpData(compressedBytes)) ~> routes ~> check {
      status should be(NoContent)
    }

    db.withSession { implicit session =>
      boxDao.listInboxImages.length should be(1)
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
      PostAsAdmin("/api/boxes/createconnection", RemoteBoxConnectionData("hosp2")) ~> routes ~> check {
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

      val dataset = DicomUtil.loadDataset(decompress(responseAs[Array[Byte]]), true)
      dataset should not be (null)
    }
  }

  it should "remove outbox entry and add sent entry when done is received" in {
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

    // check that sent table is empty at this stage
    GetAsUser("/api/sent") ~> routes ~> check {
      status should be(OK)
      responseAs[List[SentEntry]].size should be(0)
    }

    // send done
    Post(s"/api/box/${uniBox.token}/outbox/done", outboxEntry) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outbox to check that outbox is empty
    Get(s"/api/box/${uniBox.token}/outbox/poll") ~> routes ~> check {
      status should be(NotFound)
    }

    // check that sent table has an entry now
    val sentEntries =
      GetAsUser("/api/sent") ~> routes ~> check {
        val sentEntries = responseAs[List[SentEntry]]
        sentEntries.size should be(1)
        sentEntries
      }

    GetAsUser(s"/api/sent/${sentEntries.head.id}/images") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].size should be(1)
    }
  }

  it should "mark correct transaction as failed when failed message is received" in {
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

    // send failed
    Post(s"/api/box/${uniBox.token}/outbox/failed", FailedOutboxEntry(outboxEntry, "error message")) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outbox to check that outbox contains no valid entries
    Get(s"/api/box/${uniBox.token}/outbox/poll") ~> routes ~> check {
      status should be(NotFound)
    }

    // check contents of outbox, should contain one failed entry
    GetAsUser("/api/outbox") ~> routes ~> check {
      val outboxEntries = responseAs[Seq[OutboxEntry]]
      outboxEntries.size should be(1)
      outboxEntries(0).failed should be(true)
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
    val compressedArray = Get(s"/api/box/${uniBox.token}/outbox?transactionid=$transactionId&sequencenumber=$sequenceNumber") ~> routes ~> check {
      status should be(OK)
      responseAs[Array[Byte]]
    }
    val dataset = DicomUtil.loadDataset(decompress(compressedArray), false)
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