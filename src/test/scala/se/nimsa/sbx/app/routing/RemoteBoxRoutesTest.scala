package se.nimsa.sbx.app.routing

import java.util.UUID

import scala.math.abs
import scala.slick.driver.H2Driver

import org.scalatest.FlatSpec
import org.scalatest.Matchers

import se.nimsa.sbx.anonymization.AnonymizationProtocol._
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

  def dbUrl() = "jdbc:h2:mem:remoteboxroutestest;DB_CLOSE_DELAY=-1"

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

  it should "add a record of the received image connected to the incoming entry" in {

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
      boxDao.listIncomingImages should have length 1
    }
  }

  it should "return unauthorized when polling outgoing with unvalid token" in {
    Get(s"/api/box/abc/outgoing/poll") ~> sealRoute(routes) ~> check {
      status should be(Unauthorized)
    }
  }

  it should "return not found when polling empty outgoing" in {
    // first, add a box on the poll (university) side
    val uniBox =
      PostAsAdmin("/api/boxes/createconnection", RemoteBoxConnectionData("hosp2")) ~> routes ~> check {
        status should be(Created)
        responseAs[Box]
      }

    Get(s"/api/box/${uniBox.token}/outgoing/poll") ~> sealRoute(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "return OutgoingEntryImage when polling non empty outgoing" in {
    // first, add a box on the poll (university) side
    val uniBox = addPollBox("hosp3")

    // send image which adds outgoing entry
    PostAsUser(s"/api/boxes/${uniBox.id}/send", Seq(ImageTagValues(1, Seq.empty))) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outgoing
    Get(s"/api/box/${uniBox.token}/outgoing/poll") ~> routes ~> check {
      status should be(OK)

      val entryImage = responseAs[OutgoingEntryImage]

      entryImage.entry.remoteBoxId should be(uniBox.id)
      entryImage.entry.transactionId should not be (0)
      entryImage.entry.sentImageCount shouldBe 0
      entryImage.entry.totalImageCount should be(1)
      entryImage.entry.status shouldBe TransactionStatus.WAITING
    }
  }

  it should "return an image file when requesting outgoing entry" in {
    // add image (image will get id 1)
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd)

    // first, add a box on the poll (university) side
    val uniBox = addPollBox("hosp4")

    // send image which adds outgoing entry
    PostAsUser(s"/api/boxes/${uniBox.id}/send", Seq(ImageTagValues(1, Seq.empty))) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outgoing
    val entryImage =
      Get(s"/api/box/${uniBox.token}/outgoing/poll") ~> routes ~> check {
        status should be(OK)
        responseAs[OutgoingEntryImage]
      }

    // get image
    Get(s"/api/box/${uniBox.token}/outgoing?transactionid=${entryImage.entry.transactionId}") ~> routes ~> check {
      status should be(OK)

      contentType should be(ContentTypes.`application/octet-stream`)

      val dataset = DicomUtil.loadDataset(decompress(responseAs[Array[Byte]]), true)
      dataset should not be (null)
    }
  }

  it should "mark outgoing image as sent when done is received" in {
    // add image (image will get id 1)
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd)

    // first, add a box on the poll (university) side
    val uniBox = addPollBox("hosp5")

    // send image which adds outgoing entry
    PostAsUser(s"/api/boxes/${uniBox.id}/send", Seq(ImageTagValues(1, Seq.empty))) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outgoing
    val entryImage =
      Get(s"/api/box/${uniBox.token}/outgoing/poll") ~> routes ~> check {
        status should be(OK)
        responseAs[OutgoingEntryImage]
      }

    // check that outgoing image is not marked as sent at this stage
    entryImage.image.sent shouldBe false

    // send done
    Post(s"/api/box/${uniBox.token}/outgoing/done", entryImage) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outgoing to check that outgoing is empty
    Get(s"/api/box/${uniBox.token}/outgoing/poll") ~> routes ~> check {
      status should be(NotFound)
    }

        db.withSession { implicit session =>
      boxDao.listOutgoingImages.head.sent shouldBe true
    }

    GetAsUser(s"/api/sent/${entryImage.entry.id}/images") ~> routes ~> check {
      status shouldBe OK
      responseAs[List[Image]] should have length 1
    }
  }

  it should "mark correct transaction as failed when failed message is received" in {
    // add image (image will get id 1)
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd)

    // first, add a box on the poll (university) side
    val uniBox = addPollBox("hosp5")

    // send image which adds outgoing entry
    PostAsUser(s"/api/boxes/${uniBox.id}/send", Seq(ImageTagValues(1, Seq.empty))) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outgoing
    val entryImage =
      Get(s"/api/box/${uniBox.token}/outgoing/poll") ~> routes ~> check {
        status should be(OK)
        responseAs[OutgoingEntryImage]
      }

    // send failed
    Post(s"/api/box/${uniBox.token}/outgoing/failed", FailedOutgoingEntryImage(entryImage, "error message")) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outgoing to check that outgoing contains no valid entries
    Get(s"/api/box/${uniBox.token}/outgoing/poll") ~> routes ~> check {
      status should be(NotFound)
    }

    // check contents of outgoing, should contain one failed entry
    GetAsUser("/api/boxes/outgoing") ~> routes ~> check {
      val outgoingEntries = responseAs[Seq[OutgoingEntry]]
      outgoingEntries should have length 1
      outgoingEntries(0).status shouldBe TransactionStatus.FAILED
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

    // send image which adds outgoing entry
    PostAsUser(s"/api/boxes/${uniBox.id}/send", Seq(imageTagValues)) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outgoing
    val entryImage =
      Get(s"/api/box/${uniBox.token}/outgoing/poll") ~> routes ~> check {
        status should be(OK)
        responseAs[OutgoingEntryImage]
      }

    // get image
    val transactionId = entryImage.entry.transactionId
    val compressedArray = Get(s"/api/box/${uniBox.token}/outgoing?transactionid=$transactionId") ~> routes ~> check {
      status should be(OK)
      responseAs[Array[Byte]]
    }
    val dataset = DicomUtil.loadDataset(decompress(compressedArray), false)
    dataset.getString(PatientName.dicomTag) should be("TEST NAME") // mapped
    dataset.getString(PatientID.dicomTag) should be("TEST ID") // mapped
    dataset.getString(PatientBirthDate.dicomTag) should be("19601010") // mapped
    dataset.getString(PatientSex.dicomTag) should be(patient.patientSex.value) // not mapped

    // send done
    Post(s"/api/box/${uniBox.token}/outgoing/done", entryImage) ~> routes ~> check {
      status should be(NoContent)
    }
  }

}