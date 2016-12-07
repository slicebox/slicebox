package se.nimsa.sbx.box

import akka.actor.{ActorSystem, Props, actorRef2Scala}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout.durationToTimeout
import org.scalatest._
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol.ImageDeleted
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.metadata.MetaDataDAO
import se.nimsa.sbx.storage.{RuntimeStorage, StorageServiceActor}

import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

class BoxServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("BoxServiceActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:boxserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val storage = new RuntimeStorage

  val boxDao = new BoxDAO(H2Driver)
  val metaDataDao = new MetaDataDAO(H2Driver)

  db.withSession { implicit session =>
    boxDao.create
    metaDataDao.create
  }

  val storageService = system.actorOf(Props(new StorageServiceActor(storage)), name = "StorageService")
  val boxService = system.actorOf(Props(new BoxServiceActor(dbProps, "http://testhost:1234", 5.minutes)), name = "BoxService")

  override def afterEach() =
    db.withSession { implicit session =>
      metaDataDao.clear
      boxDao.clear
      storage.clear()
    }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A BoxServiceActor" should {

    "create incoming transaction for first file in transaction" in {
      db.withSession { implicit session =>

        val box = boxDao.insertBox(Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false))

        boxService ! UpdateIncoming(box, 123, 1, 2, 2, overwrite = false)

        expectMsgType[IncomingUpdated]

        val incomingTransactions = boxDao.listIncomingTransactions(0, 10)

        incomingTransactions should have length 1
        incomingTransactions.foreach { incomingTransaction =>
          incomingTransaction.boxId should be(box.id)
          incomingTransaction.outgoingTransactionId should be(123)
          incomingTransaction.receivedImageCount should be(1)
          incomingTransaction.totalImageCount should be(2)
        }

        val incomingImages = boxDao.listIncomingImages
        incomingImages should have length 1
      }
    }

    "update incoming transaction for next file in transaction" in {
      db.withSession { implicit session =>

        val box = boxDao.insertBox(Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false))

        boxService ! UpdateIncoming(box, 123, 1, 3, 4, overwrite = false)
        expectMsgType[IncomingUpdated]

        boxService ! UpdateIncoming(box, 123, 2, 3, 5, overwrite = false)
        expectMsgType[IncomingUpdated]

        val incomingTransactions = boxDao.listIncomingTransactions(0, 10)

        incomingTransactions.size should be(1)
        incomingTransactions.foreach { incomingTransaction =>
          incomingTransaction.boxId should be(box.id)
          incomingTransaction.outgoingTransactionId should be(123)
          incomingTransaction.receivedImageCount should be(2)
          incomingTransaction.totalImageCount should be(3)
        }

        val incomingImages = boxDao.listIncomingImages
        incomingImages should have length 2

      }
    }

    "return no outgoing transaction when polling and outgoing is empty" in {
      db.withSession { implicit session =>
        val box = boxDao.insertBox(Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false))

        db.withSession { implicit session =>
          boxDao.listOutgoingTransactions(0, 1) shouldBe empty
        }

        boxService ! PollOutgoing(box)

        expectMsg(None)
      }
    }

    "return first outgoing transaction image from the least recent outgoing transaction when receiving poll message" in {
      db.withSession { implicit session =>
        val imageId = 123
        val box = boxDao.insertBox(Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false))

        // insert images with sequence numbers out of order
        val transaction1 = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, box.id, box.name, 0, 1, 123, 123, TransactionStatus.WAITING))
        boxDao.insertOutgoingImage(OutgoingImage(-1, transaction1.id, imageId, 5, sent = false))
        boxDao.insertOutgoingImage(OutgoingImage(-1, transaction1.id, imageId, 4, sent = false))

        val transaction2 = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, box.id, box.name, 0, 1, 124, 124, TransactionStatus.WAITING))
        boxDao.insertOutgoingImage(OutgoingImage(-1, transaction2.id, imageId, 2, sent = false))
        boxDao.insertOutgoingImage(OutgoingImage(-1, transaction2.id, imageId, 1, sent = false))

        boxService ! PollOutgoing(box)

        expectMsgPF() {
          case Some(OutgoingTransactionImage(dbTransaction, image)) =>
            dbTransaction.boxId should be(box.id)
            dbTransaction.boxName should be("some box")
            dbTransaction.sentImageCount should be(0)
            dbTransaction.totalImageCount should be(1)
            image.imageId should be(imageId)
            image.sequenceNumber should be(4)
            image.sent should be(false)
            image.outgoingTransactionId should be(transaction1.id)
        }
      }
    }

    "create incoming transaction when receiving the first incoming file in a transaction" in {

      val box = Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false)

      val sequenceNumber = 1
      val totalImageCount = 55
      boxService ! UpdateIncoming(box, 32, sequenceNumber, totalImageCount, 33, overwrite = false)

      expectMsgPF() {
        case IncomingUpdated(transaction) =>
          transaction.receivedImageCount shouldBe sequenceNumber
          transaction.totalImageCount shouldBe totalImageCount
          transaction.status shouldBe TransactionStatus.PROCESSING
      }
      
      db.withSession { implicit session =>
        boxDao.listIncomingTransactions(0, 10) should have length 1
        boxDao.listIncomingImages should have length 1
      }
      
    }

    "mark incoming transaction as finished when receiving the UpdateIncoming message for the last file of the transaction" in {

      val box = Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false)

      val totalImageCount = 2
      boxService ! UpdateIncoming(box, 32, sequenceNumber = 1, totalImageCount, 33, overwrite = false)

      expectMsgPF() {
        case IncomingUpdated(transaction) =>
          transaction.receivedImageCount shouldBe 1
          transaction.totalImageCount shouldBe totalImageCount
          transaction.status shouldBe TransactionStatus.PROCESSING
      }
      
      boxService ! UpdateIncoming(box, 32, sequenceNumber = 2, totalImageCount, 33, overwrite = false)
      
      expectMsgPF() {
        case IncomingUpdated(transaction) =>
          transaction.receivedImageCount shouldBe 2
          transaction.totalImageCount shouldBe totalImageCount
          transaction.status shouldBe TransactionStatus.FINISHED
      }
      
      db.withSession { implicit session =>
        boxDao.listIncomingTransactions(0, 10) should have length 1
        boxDao.listIncomingImages should have length 2
      }
    }

    "mark incoming transaction as failed when receiving the UpdateIncoming message for the last file of the transaction and the number of images in the transactions does not match the number of incoming images stored in the database" in {

      val box = Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false)

      val totalImageCount = 3
      
      boxService ! UpdateIncoming(box, 32, sequenceNumber = 1, totalImageCount, 33, overwrite = false)
      expectMsgType[IncomingUpdated]
      
      boxService ! UpdateIncoming(box, 32, sequenceNumber = 3, totalImageCount, 33, overwrite = false)

      expectMsgPF() {
        case IncomingUpdated(transaction) =>
          transaction.receivedImageCount shouldBe 3
          transaction.totalImageCount shouldBe totalImageCount
          transaction.status shouldBe TransactionStatus.FAILED
      }      
    }

    "remove all related box tag values when an outgoing transaction is removed" in {
      db.withSession { implicit session =>
        val box = boxDao.insertBox(Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false))

        val (_, _, _, i1, i2, i3) = insertMetadata

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

        boxService ! SendToRemoteBox(box, imageTagValuesSeq)

        expectMsgPF() {
          case ImagesAddedToOutgoing(boxId, imageIds) =>
            boxId should be(box.id)
            imageIds should be(Seq(i1.id, i2.id, i3.id))
        }

        val outgoingTransactions = boxDao.listOutgoingTransactions(0, 10)
        outgoingTransactions should have length 1
        val outgoingImages = boxDao.listOutgoingImages
        outgoingImages should have length 3

        boxDao.listOutgoingTagValues.size should be(3 * 3)
        boxDao.tagValuesByOutgoingTransactionImage(outgoingTransactions.head.id, outgoingImages.head.id) should have length 3
        boxDao.tagValuesByOutgoingTransactionImage(outgoingTransactions.head.id, outgoingImages(1).id) should have length 3
        boxDao.tagValuesByOutgoingTransactionImage(outgoingTransactions.head.id, outgoingImages(2).id) should have length 3

        boxService ! RemoveOutgoingTransaction(outgoingTransactions.head.id)

        expectMsgType[OutgoingTransactionRemoved]

        boxDao.listOutgoingTagValues shouldBe empty
      }
    }

    "remove incoming images when the related incoming transaction is removed" in {
      db.withSession { implicit session =>
        val box = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false))

        boxService ! UpdateIncoming(box, 123, 1, 3, 4, overwrite = false)
        expectMsgType[IncomingUpdated]

        boxService ! UpdateIncoming(box, 123, 2, 3, 5, overwrite = false)
        expectMsgType[IncomingUpdated]

        val incomingTransactions = boxDao.listIncomingTransactions(0, 10)
        incomingTransactions.size should be(1)

        val incomingTransaction = incomingTransactions.head
        val incomingImages = boxDao.listIncomingImagesForIncomingTransactionId(incomingTransaction.id)
        incomingImages.size should be(2)

        boxService ! RemoveIncomingTransaction(incomingTransaction.id)
        expectMsg(IncomingTransactionRemoved(incomingTransaction.id))

        boxDao.listIncomingImagesForIncomingTransactionId(incomingTransaction.id).size should be(0)
        boxDao.listIncomingImages.size should be(0)
      }
    }

    "remove incoming transaction image when deleted from storage" in {
      db.withSession { implicit session =>
        val box = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false))

        // insert incoming images (2)
        boxService ! UpdateIncoming(box, 123, 1, 3, 4, overwrite = false)
        expectMsgType[IncomingUpdated]

        boxService ! UpdateIncoming(box, 123, 2, 3, 5, overwrite = false)
        expectMsgType[IncomingUpdated]

        boxDao.listIncomingImages.size should be(2)
        boxService ! ImageDeleted(4)
        expectNoMsg
        boxDao.listIncomingImages.size should be(1)
      }
    }

    "remove outgoing transaction image when deleted from storage" in {
      db.withSession { implicit session =>
        val box = boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false))

        // insert outgoing images (3)
        val (_, _, _, i1, i2, i3) = insertMetadata
        val imageTagValuesSeq = Seq(ImageTagValues(i1.id, Seq()), ImageTagValues(i2.id, Seq()), ImageTagValues(i3.id, Seq()))

        boxService ! SendToRemoteBox(box, imageTagValuesSeq)
        expectMsgType[ImagesAddedToOutgoing]

        boxDao.listOutgoingImages.size should be(3)
        boxService ! ImageDeleted(i2.id)
        expectNoMsg
        boxDao.listOutgoingImages.size should be(2)
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