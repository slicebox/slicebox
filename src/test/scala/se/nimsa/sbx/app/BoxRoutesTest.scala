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

  it should "return a no content message when asked to send patients" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/sendpatients", BoxSendData(Seq(1), Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a no content message when asked to send patients with empty patient ids list" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/sendpatients", BoxSendData(Seq.empty, Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a not found message when asked to send patients with unknown box id" in {
    PostAsAdmin("/api/boxes/999/sendpatients", BoxSendData(Seq(1), Seq.empty)) ~> sealRoute(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "return a no content message when asked to send studies" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/sendstudies", BoxSendData(Seq(1), Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a no content message when asked to send studies with empty study ids list" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/sendstudies", BoxSendData(Seq.empty, Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a not found message when asked to send studies with unknown box id" in {
    PostAsAdmin("/api/boxes/999/sendstudies", BoxSendData(Seq(1), Seq.empty)) ~> sealRoute(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "return a no content message when asked to send series" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/sendseries", BoxSendData(Seq(1), Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a no content message when asked to send series with empty series ids list" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/sendseries", BoxSendData(Seq.empty, Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a not found message when asked to send series with unknown box id" in {
    PostAsAdmin("/api/boxes/999/sendseries", BoxSendData(Seq(1), Seq.empty)) ~> sealRoute(routes) ~> check {
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
    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(getClass().getResource(fileName).toURI())
    val bytes = DicomUtil.toAnonymizedByteArray(dcmPath)

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

    // send series which adds outbox entry
    val seriesId = 1
    PostAsUser(s"/api/boxes/${uniBox.id}/sendseries", BoxSendData(Seq(seriesId), Seq.empty)) ~> routes ~> check {
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

  it should "return a non-empty result when listing outbox entries for sent files" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/sendseries", BoxSendData(Seq(1), Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }
    GetAsUser("/api/outbox") ~> routes ~> check {
      status should be(OK)
      responseAs[List[OutboxEntryInfo]].length should be > 0
    }
  }

  it should "return an image file when requesting outbox entry" in {
    // add image (image will get id 1)
    val fileName = "anon270.dcm"
    val file = new File(getClass().getResource(fileName).toURI())
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd)

    // first, add a box on the poll (university) side
    val uniBox = addPollBox("hosp4")

    // send series which adds outbox entry
    val seriesId = 1
    PostAsUser(s"/api/boxes/${uniBox.id}/sendseries", BoxSendData(Seq(seriesId), Seq.empty)) ~> routes ~> check {
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
    val fileName = "anon270.dcm"
    val file = new File(getClass().getResource(fileName).toURI())
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd)

    // first, add a box on the poll (university) side
    val uniBox = addPollBox("hosp5")

    // send series which adds outbox entry
    val seriesId = 1
    PostAsUser(s"/api/boxes/${uniBox.id}/sendseries", BoxSendData(Seq(seriesId), Seq.empty)) ~> routes ~> check {
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

  it should "correctly map dicom attributes according to supplied mapping when sending a series" in {
    // add image (image will get id 1)
    val fileName = "anon270.dcm"
    val file = new File(getClass().getResource(fileName).toURI())
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd)

    // first, add a box on the poll (university) side
    val uniBox = addPollBox("hosp6")

    // create attribute mappings
    val patient = GetAsUser(s"/api/metadata/patients") ~> routes ~> check {
      responseAs[List[Patient]].apply(0)
    }

    val seriesId = 1

    val tagValues = Seq(
      BoxSendTagValue(seriesId, PatientName.dicomTag, "TEST NAME"),
      BoxSendTagValue(seriesId, PatientID.dicomTag, "TEST ID"),
      BoxSendTagValue(seriesId, PatientBirthDate.dicomTag, "19601010"))

    // send series which adds outbox entry
    PostAsUser(s"/api/boxes/${uniBox.id}/sendseries", BoxSendData(Seq(seriesId), tagValues)) ~> routes ~> check {
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

  it should "provide a list of anonymization keys" in {
    db.withSession { implicit session =>
      val key1 = AnonymizationKey(-1, new Date().getTime, 1, 1234, "remote box", "pat name", "anon pat name", "pat id", "anon pat id", "19700101", "stuid", "anon stuid", "study desc", "study id", "acc num", "seuid", "anon seuid", "foruid", "anon foruid")
      val key2 = key1.copy(patientName = "pat name 2", anonPatientName = "anon pat name 2")
      val insertedKey1 = boxDao.insertAnonymizationKey(key1)
      val insertedKey2 = boxDao.insertAnonymizationKey(key2)
      GetAsUser("/api/boxes/anonymizationkeys") ~> routes ~> check {
        status should be(OK)
        responseAs[List[AnonymizationKey]] should be(List(insertedKey1, insertedKey2))
      }
    }
  }

  it should "provide a list of sorted anonymization keys supporting startindex and count" in {
    db.withSession { implicit session =>
      val key1 = AnonymizationKey(-1, new Date().getTime, 1, 1234, "remote box", "B", "anon B", "pat id", "anon pat id", "19700101", "stuid", "anon stuid", "study desc", "study id", "acc num", "seuid", "anon seuid", "foruid", "anon foruid")
      val key2 = key1.copy(patientName = "A", anonPatientName = "anon A")
      val insertedKey1 = boxDao.insertAnonymizationKey(key1)
      val insertedKey2 = boxDao.insertAnonymizationKey(key2)
      GetAsUser("/api/boxes/anonymizationkeys?startindex=0&count=1&orderby=patientname&orderascending=true") ~> routes ~> check {
        status should be(OK)
        val keys = responseAs[List[AnonymizationKey]]
        keys.length should be(1)
        keys(0) should be(insertedKey2)
      }
    }
  }

  it should "respond with 400 Bad Request when sorting anonymization keys by a non-existing property" in {
    GetAsUser("/api/boxes/anonymizationkeys?orderby=xyz") ~> routes ~> check {
      status should be(BadRequest)
    }
  }

}