package se.nimsa.sbx.box

import akka.actor.{ActorRef, ActorSystem, Props, actorRef2Scala}
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest._
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.ImagesDeleted
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.metadata.MetaDataDAO
import se.nimsa.sbx.storage.{RuntimeStorage, StorageServiceActor}
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.DurationInt

class BoxServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("BoxServiceActorTestSystem"))

  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val dbConfig = TestUtil.createTestDb("boxserviceactortest")

  val storage = new RuntimeStorage

  val boxDao = new BoxDAO(dbConfig)
  val metaDataDao = new MetaDataDAO(dbConfig)

  await(metaDataDao.create())
  await(boxDao.create())

  val storageService: ActorRef = system.actorOf(Props(new StorageServiceActor(storage)), name = "StorageService")
  val boxService: ActorRef = system.actorOf(Props(new BoxServiceActor(boxDao, "http://testhost:1234", storage)), name = "BoxService")

  override def afterEach(): Unit = {
    storage.clear()
    await(Future.sequence(Seq(
      metaDataDao.clear(),
      boxDao.clear()
    )))
  }

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  "A BoxServiceActor" should {

    "create incoming transaction for first file in transaction" in {
      val box = await(boxDao.insertBox(Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false)))

      boxService ! UpdateIncoming(box, 123, 1, 2, 2, overwrite = false)

      expectMsgType[IncomingUpdated]

      val incomingTransactions = await(boxDao.listIncomingTransactions(0, 10))

      incomingTransactions should have length 1
      incomingTransactions.foreach { incomingTransaction =>
        incomingTransaction.boxId should be(box.id)
        incomingTransaction.outgoingTransactionId should be(123)
        incomingTransaction.receivedImageCount should be(1)
        incomingTransaction.totalImageCount should be(2)
      }

      val incomingImages = await(boxDao.listIncomingImages)
      incomingImages should have length 1
    }

    "update incoming transaction for next file in transaction" in {
      val box = await(boxDao.insertBox(Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false)))

      boxService ! UpdateIncoming(box, 123, 1, 3, 4, overwrite = false)
      expectMsgType[IncomingUpdated]

      boxService ! UpdateIncoming(box, 123, 2, 3, 5, overwrite = false)
      expectMsgType[IncomingUpdated]

      val incomingTransactions = await(boxDao.listIncomingTransactions(0, 10))

      incomingTransactions.size should be(1)
      incomingTransactions.foreach { incomingTransaction =>
        incomingTransaction.boxId should be(box.id)
        incomingTransaction.outgoingTransactionId should be(123)
        incomingTransaction.receivedImageCount should be(2)
        incomingTransaction.totalImageCount should be(3)
      }

      val incomingImages = await(boxDao.listIncomingImages)
      incomingImages should have length 2

    }

    "return no outgoing transaction when polling and outgoing is empty" in {
      val box = await(boxDao.insertBox(Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false)))

      await(boxDao.listOutgoingTransactions(0, 1)) shouldBe empty

      boxService ! PollOutgoing(box)

      expectMsg(None)
    }

    "return first outgoing transaction image from the least recent outgoing transaction when receiving poll message" in {
      val imageId = 123
      val box = await(boxDao.insertBox(Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false)))

      // insert images with sequence numbers out of order
      val transaction1 = await(boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, box.id, box.name, 0, 1, 123, 123, TransactionStatus.WAITING)))
      await(boxDao.insertOutgoingImage(OutgoingImage(-1, transaction1.id, imageId, 5, sent = false)))
      await(boxDao.insertOutgoingImage(OutgoingImage(-1, transaction1.id, imageId, 4, sent = false)))

      val transaction2 = await(boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, box.id, box.name, 0, 1, 124, 124, TransactionStatus.WAITING)))
      await(boxDao.insertOutgoingImage(OutgoingImage(-1, transaction2.id, imageId, 2, sent = false)))
      await(boxDao.insertOutgoingImage(OutgoingImage(-1, transaction2.id, imageId, 1, sent = false)))

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

      await(boxDao.listIncomingTransactions(0, 10)) should have length 1
      await(boxDao.listIncomingImages) should have length 1
    }

    "update incoming transaction status" in {

      val box = Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false)

      val transactionId = 32
      boxService ! UpdateIncoming(box, transactionId, sequenceNumber = 1, totalImageCount = 55, 33, overwrite = false)
      expectMsgType[IncomingUpdated]

      boxService ! SetIncomingTransactionStatus(box.id, transactionId, TransactionStatus.FINISHED)
      expectMsg(Some(IncomingTransactionStatusUpdated))

      val transactions = await(boxDao.listIncomingTransactions(0, 10))
      transactions should have length 1
      transactions.head.status shouldBe TransactionStatus.FINISHED
    }

    "mark incoming transaction as processing until all files have been received" in {

      val box = Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false)

      val totalImageCount = 3

      boxService ! UpdateIncoming(box, 32, sequenceNumber = 1, totalImageCount, 33, overwrite = false)
      expectMsgType[IncomingUpdated]

      boxService ! UpdateIncoming(box, 32, sequenceNumber = 3, totalImageCount, 33, overwrite = false)

      expectMsgPF() {
        case IncomingUpdated(transaction) =>
          transaction.receivedImageCount shouldBe 2
          transaction.addedImageCount shouldBe 2
          transaction.totalImageCount shouldBe totalImageCount
          transaction.status shouldBe TransactionStatus.PROCESSING
      }
    }

    "remove all related box tag values when an outgoing transaction is removed" in {
      val box = await(boxDao.insertBox(Box(-1, "some box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false)))

      val (_, _, _, i1, i2, i3) = await(insertMetadata())

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

      val outgoingTransactions = await(boxDao.listOutgoingTransactions(0, 10))
      outgoingTransactions should have length 1
      val outgoingImages = await(boxDao.listOutgoingImages)
      outgoingImages should have length 3

      await(boxDao.listOutgoingTagValues).size should be(3 * 3)
      await(boxDao.tagValuesByOutgoingTransactionImage(outgoingTransactions.head.id, outgoingImages.head.id)) should have length 3
      await(boxDao.tagValuesByOutgoingTransactionImage(outgoingTransactions.head.id, outgoingImages(1).id)) should have length 3
      await(boxDao.tagValuesByOutgoingTransactionImage(outgoingTransactions.head.id, outgoingImages(2).id)) should have length 3

      boxService ! RemoveOutgoingTransaction(outgoingTransactions.head.id)

      expectMsgType[OutgoingTransactionRemoved]

      await(boxDao.listOutgoingTagValues) shouldBe empty
    }

    "remove incoming images when the related incoming transaction is removed" in {
      val box = await(boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false)))

      boxService ! UpdateIncoming(box, 123, 1, 3, 4, overwrite = false)
      expectMsgType[IncomingUpdated]

      boxService ! UpdateIncoming(box, 123, 2, 3, 5, overwrite = false)
      expectMsgType[IncomingUpdated]

      val incomingTransactions = await(boxDao.listIncomingTransactions(0, 10))
      incomingTransactions.size should be(1)

      val incomingTransaction = incomingTransactions.head
      val incomingImages = await(boxDao.listIncomingImagesForIncomingTransactionId(incomingTransaction.id))
      incomingImages.size should be(2)

      boxService ! RemoveIncomingTransaction(incomingTransaction.id)
      expectMsg(IncomingTransactionRemoved(incomingTransaction.id))

      await(boxDao.listIncomingImagesForIncomingTransactionId(incomingTransaction.id)).size should be(0)
      await(boxDao.listIncomingImages).size should be(0)
    }

    "remove incoming transaction image when deleted from storage" in {
      val box = await(boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false)))

      // insert incoming images (2)
      boxService ! UpdateIncoming(box, 123, 1, 3, 4, overwrite = false)
      expectMsgType[IncomingUpdated]

      boxService ! UpdateIncoming(box, 123, 2, 3, 5, overwrite = false)
      expectMsgType[IncomingUpdated]

      await(boxDao.listIncomingImages).size should be(2)
      boxService ! ImagesDeleted(Seq(4))
      expectNoMessage(3.seconds)
      await(boxDao.listIncomingImages).size should be(1)
    }

    "remove outgoing transaction image when deleted from storage" in {
      val box = await(boxDao.insertBox(Box(-1, "some remote box", "abc", "https://someurl.com", BoxSendMethod.POLL, online = false)))

      // insert outgoing images (3)
      val (_, _, _, i1, i2, i3) = await(insertMetadata())
      val imageTagValuesSeq = Seq(ImageTagValues(i1.id, Seq()), ImageTagValues(i2.id, Seq()), ImageTagValues(i3.id, Seq()))

      boxService ! SendToRemoteBox(box, imageTagValuesSeq)
      expectMsgType[ImagesAddedToOutgoing]

      await(boxDao.listOutgoingImages).size should be(3)
      boxService ! ImagesDeleted(Seq(i2.id))
      expectNoMessage(3.seconds)
      await(boxDao.listOutgoingImages).size should be(2)
    }

  }
  def insertMetadata(): Future[(Patient, Study, Series, Image, Image, Image)] = for {
    p1 <- metaDataDao.insert(Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate("2000-01-01"), PatientSex("M")))
    s1 <- metaDataDao.insert(Study(-1, p1.id, StudyInstanceUID("stuid1"), StudyDescription("stdesc1"), StudyDate("19990101"), StudyID("stid1"), AccessionNumber("acc1"), PatientAge("12Y")))
    r1 <- metaDataDao.insert(Series(-1, s1.id, SeriesInstanceUID("seuid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"), Manufacturer("manu1"), StationName("station1"), FrameOfReferenceUID("frid1")))
    i1 <- metaDataDao.insert(Image(-1, r1.id, SOPInstanceUID("1.1"), ImageType("t1"), InstanceNumber("1")))
    i2 <- metaDataDao.insert(Image(-1, r1.id, SOPInstanceUID("1.2"), ImageType("t1"), InstanceNumber("1")))
    i3 <- metaDataDao.insert(Image(-1, r1.id, SOPInstanceUID("1.3"), ImageType("t1"), InstanceNumber("1")))
  } yield (p1, s1, r1, i1, i2, i3)

}