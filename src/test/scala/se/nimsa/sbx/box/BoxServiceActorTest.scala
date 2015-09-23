package se.nimsa.sbx.box

import java.nio.file.Files
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.storage.MetaDataDAO
import se.nimsa.sbx.storage.PropertiesDAO
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.storage.StorageServiceActor
import se.nimsa.sbx.util.TestUtil
import akka.util.Timeout
import scala.concurrent.duration.DurationInt

class BoxServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("BoxServiceActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:boxserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val storage = Files.createTempDirectory("slicebox-test-storage-")

  val boxDao = new BoxDAO(H2Driver)
  val metaDataDao = new MetaDataDAO(H2Driver)

  db.withSession { implicit session =>
    boxDao.create
    metaDataDao.create
  }

  val storageService = system.actorOf(Props(new StorageServiceActor(dbProps, storage)), name = "StorageService")
  val boxService = system.actorOf(Props(new BoxServiceActor(dbProps, storage, "http://testhost:1234", Timeout(30.seconds))), name = "BoxService")

  override def afterEach() =
    db.withSession { implicit session =>
      metaDataDao.clear
      boxDao.clear
    }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }

  "A BoxServiceActor" should {

    "create inbox entry for first file in transaction" in {
      db.withSession { implicit session =>

        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))

        boxService ! UpdateInbox(remoteBox.token, 123, 1, 2, 2)

        expectMsg(InboxUpdated(remoteBox.token, 123, 1, 2))

        val inboxEntries = boxDao.listInboxEntries

        inboxEntries.size should be(1)
        inboxEntries.foreach(inboxEntry => {
          inboxEntry.remoteBoxId should be(remoteBox.id)
          inboxEntry.transactionId should be(123)
          inboxEntry.receivedImageCount should be(1)
          inboxEntry.totalImageCount should be(2)

        })
      }
    }

    "update inbox entry for next file in transaction" in {
      db.withSession { implicit session =>

        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))

        boxService ! UpdateInbox(remoteBox.token, 123, 1, 3, 4)
        expectMsg(InboxUpdated(remoteBox.token, 123, 1, 3))

        boxService ! UpdateInbox(remoteBox.token, 123, 2, 3, 5)
        expectMsg(InboxUpdated(remoteBox.token, 123, 2, 3))

        val inboxEntries = boxDao.listInboxEntries

        inboxEntries.size should be(1)
        inboxEntries.foreach(inboxEntry => {
          inboxEntry.remoteBoxId should be(remoteBox.id)
          inboxEntry.transactionId should be(123)
          inboxEntry.receivedImageCount should be(2)
          inboxEntry.totalImageCount should be(3)

        })
      }
    }

    "return OuboxEmpty for poll message when outbox is empty" in {
      db.withSession { implicit session =>
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))

        boxService ! PollOutbox(remoteBox.token)

        expectMsg(OutboxEmpty)
      }
    }

    "return first outbox entry when receiving poll message" in {
      db.withSession { implicit session =>
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))
        boxDao.insertOutboxEntry(OutboxEntry(-1, remoteBox.id, 987, 1, 2, 123, false))

        boxService ! PollOutbox(remoteBox.token)

        expectMsgPF() {
          case OutboxEntry(id, remoteBoxId, transactionId, sequenceNumber, totalImageCount, imageId, failed) =>
            remoteBoxId should be(remoteBox.id)
            transactionId should be(987)
            sequenceNumber should be(1)
            totalImageCount should be(2)
            imageId should be(123)
        }
      }
    }

    "remove all box tag values when all outbox entries for a transaction have been removed" in {
      db.withSession { implicit session =>
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))

        val (p1, s1, r1, i1, i2, i3) = insertMetadata

        val imageTagValuesSeq = Seq(
          ImageTagValues(i1.id, Seq(
            TagValue(0x00101010, "B"),
            TagValue(0x00101012, "D"),
            TagValue(0x00101014, "F"))),
          ImageTagValues(i2.id, Seq(
            TagValue(0x00101010, "B"),
            TagValue(0x00101012, "D"),
            TagValue(0x00101014, "F"))),
          ImageTagValues(i3.id, Seq(
            TagValue(0x00101010, "B"),
            TagValue(0x00101012, "D"),
            TagValue(0x00101014, "F"))))

        boxService ! SendToRemoteBox(remoteBox.id, imageTagValuesSeq)

        expectMsgPF() {
          case ImagesSent(remoteBoxId, imageIds) =>
            remoteBoxId should be(remoteBox.id)
            imageIds should be(Seq(i1.id, i2.id, i3.id))
        }

        val outboxEntries = boxDao.listOutboxEntries
        outboxEntries.size should be(3)

        val transactionId = outboxEntries(0).transactionId
        outboxEntries.map(_.transactionId).forall(_ == transactionId) should be(true)

        boxDao.listTransactionTagValues.size should be(3 * 3)
        boxDao.tagValuesByImageIdAndTransactionId(i1.id, transactionId).size should be(3)
        boxDao.tagValuesByImageIdAndTransactionId(i2.id, transactionId).size should be(3)
        boxDao.tagValuesByImageIdAndTransactionId(i3.id, transactionId).size should be(3)

        outboxEntries.map(_.id).foreach(id => boxService ! RemoveOutboxEntry(id))

        expectMsgType[OutboxEntryRemoved]
        expectMsgType[OutboxEntryRemoved]
        expectMsgType[OutboxEntryRemoved]

        boxDao.listTransactionTagValues.isEmpty should be(true)
        boxDao.tagValuesByImageIdAndTransactionId(i1.id, transactionId).isEmpty should be(true)
        boxDao.tagValuesByImageIdAndTransactionId(i2.id, transactionId).isEmpty should be(true)
        boxDao.tagValuesByImageIdAndTransactionId(i3.id, transactionId).isEmpty should be(true)
      }
    }

    "remove all box tag values when last outbox entry has been processed" in {
      db.withSession { implicit session =>
        val token = "abc"
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", token, "https://someurl.com", BoxSendMethod.POLL, false))

        val (p1, s1, r1, i1, i2, i3) = insertMetadata

        val imageTagValuesSeq = Seq(
          ImageTagValues(i1.id, Seq(
            TagValue(0x00101010, "B"),
            TagValue(0x00101012, "D"),
            TagValue(0x00101014, "F"))),
          ImageTagValues(i2.id, Seq(
            TagValue(0x00101010, "B"),
            TagValue(0x00101012, "D"),
            TagValue(0x00101014, "F"))),
          ImageTagValues(i3.id, Seq(
            TagValue(0x00101010, "B"),
            TagValue(0x00101012, "D"),
            TagValue(0x00101014, "F"))))

        boxService ! SendToRemoteBox(remoteBox.id, imageTagValuesSeq)

        expectMsgPF() {
          case ImagesSent(remoteBoxId, imageIds) =>
            remoteBoxId should be(remoteBox.id)
            imageIds should be(Seq(i1.id, i2.id, i3.id))
        }

        val outboxEntries = boxDao.listOutboxEntries
        outboxEntries.size should be(3)

        val transactionId = outboxEntries(0).transactionId
        outboxEntries.map(_.transactionId).forall(_ == transactionId) should be(true)

        boxDao.listTransactionTagValues.size should be(3 * 3)
        boxDao.tagValuesByImageIdAndTransactionId(i1.id, transactionId).size should be(3)
        boxDao.tagValuesByImageIdAndTransactionId(i2.id, transactionId).size should be(3)
        boxDao.tagValuesByImageIdAndTransactionId(i3.id, transactionId).size should be(3)

        outboxEntries.foreach(entry => boxService ! DeleteOutboxEntry(token, entry.transactionId, entry.sequenceNumber))

        expectMsg(OutboxEntryDeleted)
        expectMsg(OutboxEntryDeleted)
        expectMsg(OutboxEntryDeleted)

        boxDao.listTransactionTagValues.isEmpty should be(true)
        boxDao.tagValuesByImageIdAndTransactionId(i1.id, transactionId).isEmpty should be(true)
        boxDao.tagValuesByImageIdAndTransactionId(i2.id, transactionId).isEmpty should be(true)
        boxDao.tagValuesByImageIdAndTransactionId(i3.id, transactionId).isEmpty should be(true)
      }
    }

    "remove inbox images when the related inbox entry is removed" in {
      db.withSession { implicit session =>
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))

        boxService ! UpdateInbox(remoteBox.token, 123, 1, 3, 4)
        expectMsg(InboxUpdated(remoteBox.token, 123, 1, 3))

        boxService ! UpdateInbox(remoteBox.token, 123, 2, 3, 5)
        expectMsg(InboxUpdated(remoteBox.token, 123, 2, 3))

        val inboxEntries = boxDao.listInboxEntries
        inboxEntries.size should be(1)

        val inboxEntry = inboxEntries.head
        val inboxImages = boxDao.listInboxImagesForInboxEntryId(inboxEntry.id)
        inboxImages.size should be(2)

        boxService ! RemoveInboxEntry(inboxEntry.id)
        expectMsg(InboxEntryRemoved(inboxEntry.id))

        boxDao.listInboxImagesForInboxEntryId(inboxEntry.id).size should be(0)
        boxDao.listInboxImages.size should be(0)
      }
    }

    "add processed outbox entries to the list of sent entries, along with records of sent images" in {
      db.withSession { implicit session =>
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))
        boxDao.insertOutboxEntry(OutboxEntry(-1, remoteBox.id, 123, 1, 100, 5, false))
        boxDao.insertOutboxEntry(OutboxEntry(-1, remoteBox.id, 123, 2, 100, 33, false))

        boxService ! DeleteOutboxEntry("abc", 123, 1)
        expectMsg(OutboxEntryDeleted)

        var sentEntries = boxDao.listSentEntries
        sentEntries.size should be(1)
        boxDao.listSentImagesForSentEntryId(sentEntries.head.id).map(_.imageId) should be(List(5))

        boxService ! DeleteOutboxEntry("abc", 123, 2)
        expectMsg(OutboxEntryDeleted)

        sentEntries = boxDao.listSentEntries
        sentEntries.size should be(1)
        boxDao.listSentImagesForSentEntryId(sentEntries.head.id).map(_.imageId) should be(List(5, 33))
      }
    }

    "remove sent images when the related sent entry is removed" in {
      db.withSession { implicit session =>
        val se = boxDao.insertSentEntry(SentEntry(-1, 1, 123, 1, 2))
        boxDao.insertSentImage(SentImage(-1, se.id, 5))
        boxDao.insertSentImage(SentImage(-1, se.id, 33))
        
        boxDao.listSentEntries.size should be (1)
        boxDao.listSentImagesForSentEntryId(se.id).size should be (2)
        
        boxService ! RemoveSentEntry(se.id)
        expectMsg(SentEntryRemoved(se.id))        
        
        boxDao.listSentEntries.size should be (0)
        boxDao.listSentImagesForSentEntryId(se.id).size should be (0)        
      }
    }
  }

  def insertMetadata(implicit session: H2Driver.simple.Session) = {
    val p1 = metaDataDao.insert(Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate("2000-01-01"), PatientSex("M")))
    val s1 = metaDataDao.insert(Study(-1, p1.id, StudyInstanceUID("stuid1"), StudyDescription("stdesc1"), StudyDate("19990101"), StudyID("stid1"), AccessionNumber("acc1"), PatientAge("12Y")))
    val r1 = metaDataDao.insert(Series(-1, s1.id, SeriesInstanceUID("seuid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"), Manufacturer("manu1"), StationName("station1"), FrameOfReferenceUID("frid1")))
    val i1 = metaDataDao.insert(Image(-1, r1.id, SOPInstanceUID("1.1"), ImageType("t1"), InstanceNumber("1")))
    val i2 = metaDataDao.insert(Image(-1, r1.id, SOPInstanceUID("1.2"), ImageType("t1"), InstanceNumber("1")))
    val i3 = metaDataDao.insert(Image(-1, r1.id, SOPInstanceUID("1.3"), ImageType("t1"), InstanceNumber("1")))
    (p1, s1, r1, i1, i2, i3)
  }

}