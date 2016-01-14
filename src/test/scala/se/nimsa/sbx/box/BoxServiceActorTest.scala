package se.nimsa.sbx.box

import java.nio.file.Files

import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

import org.scalatest._

import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.util.Timeout.durationToTimeout
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.metadata.MetaDataDAO
import se.nimsa.sbx.storage.StorageServiceActor
import se.nimsa.sbx.util.TestUtil

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

  val storageService = system.actorOf(Props(new StorageServiceActor(storage, 5.minutes)), name = "StorageService")
  val boxService = system.actorOf(Props(new BoxServiceActor(dbProps, "http://testhost:1234", 5.minutes)), name = "BoxService")

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

    "create incoming entry for first file in transaction" in {
      db.withSession { implicit session =>

        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))

        boxService ! UpdateIncoming(remoteBox, 123, 2, 2)

        expectMsgType[IncomingUpdated]

        val incomingEntries = boxDao.listIncomingEntries

        incomingEntries.size should be(1)
        incomingEntries.foreach(incomingEntry => {
          incomingEntry.remoteBoxId should be(remoteBox.id)
          incomingEntry.transactionId should be(123)
          incomingEntry.receivedImageCount should be(1)
          incomingEntry.totalImageCount should be(2)

        })
      }
    }

    "update incoming entry for next file in transaction" in {
      db.withSession { implicit session =>

        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))

        boxService ! UpdateIncoming(remoteBox, 123, 3, 4)
        expectMsgType[IncomingUpdated]

        boxService ! UpdateIncoming(remoteBox, 123, 3, 5)
        expectMsgType[IncomingUpdated]

        val incomingEntries = boxDao.listIncomingEntries

        incomingEntries.size should be(1)
        incomingEntries.foreach(incomingEntry => {
          incomingEntry.remoteBoxId should be(remoteBox.id)
          incomingEntry.transactionId should be(123)
          incomingEntry.receivedImageCount should be(2)
          incomingEntry.totalImageCount should be(3)

        })
      }
    }

    "return OutgoingEmpty for poll message when outgoing is empty" in {
      db.withSession { implicit session =>
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))

        boxService ! PollOutgoing(remoteBox)

        expectMsg(OutgoingEmpty)
      }
    }

    "return first outgoing entry when receiving poll message" in {
      db.withSession { implicit session =>
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))
        boxDao.insertOutgoingEntry(OutgoingEntry(-1, remoteBox.id, remoteBox.name, 987, 1, 2, 123, TransactionStatus.WAITING))

        boxService ! PollOutgoing(remoteBox)

        expectMsgPF() {
          case OutgoingEntryImage(entry, image) =>
            entry.remoteBoxId should be(remoteBox.id)
            entry.remoteBoxName should be("some remote box")
            entry.transactionId should be(987)
            entry.totalImageCount should be(2)
            image.imageId should be(123)
            image.sent should be(false)
            image.outgoingEntryId should be(entry.id)
        }
      }
    }

    "remove all box tag values when all outgoing entries for a transaction have been removed" in {
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

        boxService ! SendToRemoteBox(remoteBox, imageTagValuesSeq)

        expectMsgPF() {
          case ImagesAddedToOutgoing(remoteBoxId, imageIds) =>
            remoteBoxId should be(remoteBox.id)
            imageIds should be(Seq(i1.id, i2.id, i3.id))
        }

        val outgoingEntries = boxDao.listOutgoingEntries
        outgoingEntries.size should be(3)

        val transactionId = outgoingEntries(0).transactionId
        outgoingEntries.map(_.transactionId).forall(_ == transactionId) should be(true)

        boxDao.listTransactionTagValues.size should be(3 * 3)
        boxDao.tagValuesByImageIdAndTransactionId(i1.id, transactionId).size should be(3)
        boxDao.tagValuesByImageIdAndTransactionId(i2.id, transactionId).size should be(3)
        boxDao.tagValuesByImageIdAndTransactionId(i3.id, transactionId).size should be(3)

        outgoingEntries.map(_.id).foreach(id => boxService ! RemoveOutgoingEntry(id))

        expectMsgType[OutgoingEntryRemoved]
        expectMsgType[OutgoingEntryRemoved]
        expectMsgType[OutgoingEntryRemoved]

        boxDao.listTransactionTagValues.isEmpty should be(true)
        boxDao.tagValuesByImageIdAndTransactionId(i1.id, transactionId).isEmpty should be(true)
        boxDao.tagValuesByImageIdAndTransactionId(i2.id, transactionId).isEmpty should be(true)
        boxDao.tagValuesByImageIdAndTransactionId(i3.id, transactionId).isEmpty should be(true)
      }
    }

    "remove all box tag values when last outgoing entry has been processed" in {
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

        boxService ! SendToRemoteBox(remoteBox, imageTagValuesSeq)

        expectMsgPF() {
          case ImagesAddedToOutgoing(remoteBoxId, imageIds) =>
            remoteBoxId should be(remoteBox.id)
            imageIds should be(Seq(i1.id, i2.id, i3.id))
        }

        val outgoingEntries = boxDao.listOutgoingEntries
        outgoingEntries.size should be(3)

        val transactionId = outgoingEntries(0).transactionId
        outgoingEntries.map(_.transactionId).forall(_ == transactionId) should be(true)

        boxDao.listTransactionTagValues.size should be(3 * 3)
        boxDao.tagValuesByImageIdAndTransactionId(i1.id, transactionId).size should be(3)
        boxDao.tagValuesByImageIdAndTransactionId(i2.id, transactionId).size should be(3)
        boxDao.tagValuesByImageIdAndTransactionId(i3.id, transactionId).size should be(3)

        outgoingEntries.foreach(entry => boxService ! RemoveOutgoingEntry(entry.id))

        expectMsg(OutgoingEntryRemoved)
        expectMsg(OutgoingEntryRemoved)
        expectMsg(OutgoingEntryRemoved)

        boxDao.listTransactionTagValues.isEmpty should be(true)
        boxDao.tagValuesByImageIdAndTransactionId(i1.id, transactionId).isEmpty should be(true)
        boxDao.tagValuesByImageIdAndTransactionId(i2.id, transactionId).isEmpty should be(true)
        boxDao.tagValuesByImageIdAndTransactionId(i3.id, transactionId).isEmpty should be(true)
      }
    }

    "remove incoming images when the related incoming entry is removed" in {
      db.withSession { implicit session =>
        val remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, false))

        boxService ! UpdateIncoming(remoteBox, 123, 3, 4)
        expectMsgType[IncomingUpdated]

        boxService ! UpdateIncoming(remoteBox, 123, 3, 5)
        expectMsgType[IncomingUpdated]

        val incomingEntries = boxDao.listIncomingEntries
        incomingEntries.size should be(1)

        val incomingEntry = incomingEntries.head
        val incomingImages = boxDao.listIncomingImagesForIncomingEntryId(incomingEntry.id)
        incomingImages.size should be(2)

        boxService ! RemoveIncomingEntry(incomingEntry.id)
        expectMsg(IncomingEntryRemoved(incomingEntry.id))

        boxDao.listIncomingImagesForIncomingEntryId(incomingEntry.id).size should be(0)
        boxDao.listIncomingImages.size should be(0)
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