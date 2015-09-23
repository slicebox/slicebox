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
import se.nimsa.sbx.storage.MetaDataDAO

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

  it should "support removing inbox entries" in {
    val inboxEntry =
      db.withSession { implicit session =>
        boxDao.insertInboxEntry(InboxEntry(-1, 1, 2, 3, 4))
      }

    DeleteAsUser(s"/api/inbox/${inboxEntry.id}") ~> routes ~> check {
      status should be(NoContent)
    }

    GetAsUser("/api/inbox") ~> routes ~> check {
      responseAs[List[InboxEntryInfo]].size should be(0)
    }
  }

  it should "support removing outbox entries" in {
    val outboxEntry =
      db.withSession { implicit session =>
        boxDao.insertOutboxEntry(OutboxEntry(-1, 1, 2, 3, 4, 5, false))
      }

    DeleteAsUser(s"/api/outbox/${outboxEntry.id}") ~> routes ~> check {
      status should be(NoContent)
    }

    GetAsUser("/api/outbox") ~> routes ~> check {
      responseAs[List[OutboxEntryInfo]].size should be(0)
    }
  }

  it should "support listing images corresponding to an inbox entry" in {
    val inboxEntry =
      db.withSession { implicit session =>
        val (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8)) =
          TestUtil.insertMetaData(metaDataDao)
        val inboxEntry = boxDao.insertInboxEntry(InboxEntry(-1, 1, 2, 3, 4))
        val inboxImage1 = boxDao.insertInboxImage(InboxImage(-1, inboxEntry.id, dbImage1.id))
        val inboxImage2 = boxDao.insertInboxImage(InboxImage(-1, inboxEntry.id, dbImage2.id))
        inboxEntry
      }
    
    GetAsUser(s"/api/inbox/${inboxEntry.id}/images") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].length should be(2)
    }
  }
  
  it should "only list images corresponding to an inbox entry that exists" in {
    val inboxEntry =
      db.withSession { implicit session =>
        val (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8)) =
          TestUtil.insertMetaData(metaDataDao)
        val inboxEntry = boxDao.insertInboxEntry(InboxEntry(-1, 1, 2, 3, 4))
        val inboxImage1 = boxDao.insertInboxImage(InboxImage(-1, inboxEntry.id, dbImage1.id))
        val inboxImage2 = boxDao.insertInboxImage(InboxImage(-1, inboxEntry.id, dbImage2.id))
        val inboxImage3 = boxDao.insertInboxImage(InboxImage(-1, inboxEntry.id, 666))
        inboxEntry
      }
    
    GetAsUser(s"/api/inbox/${inboxEntry.id}/images") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].length should be(2)
    }    
  }

}