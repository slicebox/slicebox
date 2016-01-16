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
import se.nimsa.sbx.metadata.MetaDataDAO
import org.dcm4che3.data.Tag

class BoxRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:boxroutestest;DB_CLOSE_DELAY=-1"

  val boxDao = new BoxDAO(H2Driver)
  val metaDataDao = new MetaDataDAO(H2Driver)

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

  "Box routes" should "return a success message when asked to generate a new base url" in {
    addPollBox("hosp")
  }

  it should "return a bad request message when asking to generate a new base url with a malformed request body" in {
    val malformedEntity = Seq.empty[Box]
    PostAsAdmin("/api/boxes/createconnection", malformedEntity) ~> sealRoute(routes) ~> check {
      status should be(BadRequest)
    }
  }

  it should "return a bad request message when adding two boxes with the same name" in {
    addPollBox("hosp")
    PostAsAdmin("/api/boxes/createconnection", RemoteBoxConnectionData("hosp")) ~> sealRoute(routes) ~> check {
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

  it should "return a non-empty result when listing outgoing entries" in {
    val box1 = addPollBox("hosp")
    PostAsAdmin(s"/api/boxes/${box1.id}/send", Seq(ImageTagValues(1, Seq.empty))) ~> routes ~> check {
      status should be(NoContent)
    }
    GetAsUser("/api/boxes/outgoing") ~> routes ~> check {
      status should be(OK)
      responseAs[List[OutgoingTransaction]].length should be > 0
    }
  }

  it should "support listing incoming entries" in {
    val sentTransaction =
      db.withSession { implicit session =>
        boxDao.insertIncomingTransaction(IncomingTransaction(-1, 1, "some box", 1, 3, 4, System.currentTimeMillis(), TransactionStatus.WAITING))
        boxDao.insertIncomingTransaction(IncomingTransaction(-1, 1, "some box", 2, 3, 5, System.currentTimeMillis(), TransactionStatus.WAITING))
      }

    GetAsUser("/api/boxes/incoming") ~> routes ~> check {
      responseAs[List[IncomingTransaction]].size should be(2)
    }
  }

  it should "support removing incoming entries" in {
    val entry =
      db.withSession { implicit session =>
        boxDao.insertIncomingTransaction(IncomingTransaction(-1, 1, "some box", 2, 3, 4, System.currentTimeMillis(), TransactionStatus.WAITING))
      }

    DeleteAsUser(s"/api/boxes/incoming/${entry.id}") ~> routes ~> check {
      status should be(NoContent)
    }

    GetAsUser("/api/boxes/incoming") ~> routes ~> check {
      responseAs[List[IncomingTransaction]].size should be(0)
    }
  }

  it should "support removing outgoing entries" in {
    db.withSession { implicit session =>
      val entry = boxDao.insertOutgoingTransaction(OutgoingTransaction(1, 1, "some box", 0, 1, 1000, TransactionStatus.WAITING))
      val image = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, 1, 1, false))

      DeleteAsUser(s"/api/boxes/outgoing/${entry.id}") ~> routes ~> check {
        status should be(NoContent)
      }

      GetAsUser("/api/boxes/outgoing") ~> routes ~> check {
        responseAs[List[OutgoingTransaction]].size should be(0)
      }
      
      boxDao.listOutgoingImages shouldBe empty
    }
  }

  it should "support listing images corresponding to an incoming entry" in {
    val entry =
      db.withSession { implicit session =>
        val (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8)) =
          TestUtil.insertMetaData(metaDataDao)
        val entry = boxDao.insertIncomingTransaction(IncomingTransaction(-1, 1, "some box", 2, 3, 4, System.currentTimeMillis(), TransactionStatus.WAITING))
        val image1 = boxDao.insertIncomingImage(IncomingImage(-1, entry.id, dbImage1.id))
        val image2 = boxDao.insertIncomingImage(IncomingImage(-1, entry.id, dbImage2.id))
        entry
      }

    GetAsUser(s"/api/boxes/incoming/${entry.id}/images") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].length should be(2)
    }
  }

  it should "only list images corresponding to an incoming entry that exists" in {
    val entry =
      db.withSession { implicit session =>
        val (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8)) =
          TestUtil.insertMetaData(metaDataDao)
        val entry = boxDao.insertIncomingTransaction(IncomingTransaction(-1, 1, "some box", 2, 3, 4, System.currentTimeMillis(), TransactionStatus.WAITING))
        val image1 = boxDao.insertIncomingImage(IncomingImage(-1, entry.id, dbImage1.id))
        val image2 = boxDao.insertIncomingImage(IncomingImage(-1, entry.id, dbImage2.id))
        val image3 = boxDao.insertIncomingImage(IncomingImage(-1, entry.id, 666))
        entry
      }

    GetAsUser(s"/api/boxes/incoming/${entry.id}/images") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].length should be(2)
    }
  }

  it should "support listing images corresponding to an outgoing entry" in {
    val entry =
      db.withSession { implicit session =>
        val (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8)) =
          TestUtil.insertMetaData(metaDataDao)
        val entry = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, 1, "some box", 3, 4, System.currentTimeMillis(), TransactionStatus.WAITING))
        val image1 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage1.id, 1, false))
        val image2 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage2.id, 2, false))
        entry
      }

    GetAsUser(s"/api/boxes/outgoing/${entry.id}/images") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].length should be(2)
    }
  }

  it should "only list images corresponding to an outgoing entry that exists" in {
    val entry =
      db.withSession { implicit session =>
        val (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8)) =
          TestUtil.insertMetaData(metaDataDao)
        val entry = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, 1, "some box", 3, 4, System.currentTimeMillis(), TransactionStatus.WAITING))
        val image1 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage1.id, 1, false))
        val image2 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage2.id, 2, false))
        val image3 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, 666, 3, false))
        entry
      }

    GetAsUser(s"/api/boxes/outgoing/${entry.id}/images") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].length should be(2)
    }
  }

  it should "remove related image record in incoming when an image is deleted" in {
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    val image =
      PostAsUser("/api/images", mfd) ~> routes ~> check {
        status shouldBe Created
        responseAs[Image]
      }

    val (entry, imageTransaction) =
      db.withSession { implicit session =>
        val entry = boxDao.insertIncomingTransaction(IncomingTransaction(-1, 1, "some box", 2, 3, 4, System.currentTimeMillis(), TransactionStatus.WAITING))
        val imageTransaction = boxDao.insertIncomingImage(IncomingImage(-1, entry.id, image.id))
        (entry, imageTransaction)
      }

    GetAsUser(s"/api/boxes/incoming/${entry.id}/images") ~> routes ~> check {
      status shouldBe OK
      responseAs[List[Image]] should have length 1
    }

    DeleteAsUser(s"/api/images/${imageTransaction.imageId}") ~> routes ~> check {
      status shouldBe NoContent
    }

    Thread.sleep(1000) // wait for ImageDeleted event to reach BoxServiceActor

    GetAsUser(s"/api/boxes/incoming/${entry.id}/images") ~> routes ~> check {
      status shouldBe OK
      responseAs[List[Image]] shouldBe empty
    }
  }

  it should "remove related image record in outgoing when an image is deleted" in {
    val file = TestUtil.testImageFile
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    val image =
      PostAsUser("/api/images", mfd) ~> routes ~> check {
        status shouldBe Created
        responseAs[Image]
      }

    val (entry, imageTransaction) =
      db.withSession { implicit session =>
        val entry = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, 1, "some box", 3, 4, System.currentTimeMillis(), TransactionStatus.WAITING))
        val imageTransaction = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, image.id, 1, false))
        (entry, imageTransaction)
      }

    GetAsUser(s"/api/boxes/outgoing/${entry.id}/images") ~> routes ~> check {
      status shouldBe OK
      responseAs[List[Image]] should have length 1
    }

    DeleteAsUser(s"/api/images/${imageTransaction.imageId}") ~> routes ~> check {
      status shouldBe NoContent
    }

    Thread.sleep(1000) // wait for ImageDeleted event to reach BoxServiceActor

    GetAsUser(s"/api/boxes/outgoing/${entry.id}/images") ~> routes ~> check {
      status shouldBe OK
      responseAs[List[Image]] shouldBe empty
    }
  }

}