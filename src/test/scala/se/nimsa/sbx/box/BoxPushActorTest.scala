package se.nimsa.sbx.box

import akka.testkit.TestKit
import akka.testkit.ImplicitSender
import org.scalatest._
import akka.actor.ActorSystem
import akka.actor.Props
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import se.nimsa.sbx.app.DbProps
import java.nio.file.Files
import se.nimsa.sbx.util.TestUtil
import akka.testkit.TestActorRef
import spray.http.HttpRequest
import spray.client.pipelining._
import spray.http.HttpData
import scala.concurrent.Promise
import scala.concurrent.Future
import spray.http.HttpResponse
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomProtocol._
import se.nimsa.sbx.dicom.DicomMetaDataDAO
import se.nimsa.sbx.dicom.DicomPropertiesDAO
import spray.http.StatusCodes._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt

class BoxPushActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("BoxPushActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:boxpushactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val storage = Files.createTempDirectory("slicebox-test-storage-")

  val boxDao = new BoxDAO(H2Driver)
  val dicomMetaDataDao = new DicomMetaDataDAO(H2Driver)
  val dicomPropertiesDao = new DicomPropertiesDAO(H2Driver)

  db.withSession { implicit session =>
    boxDao.create
    dicomMetaDataDao.create
    dicomPropertiesDao.create
  }

  val testBox = Box(1, "Test Box", "abc123", "testbox.com", BoxSendMethod.PUSH, false)

  val testTransactionId = 888
  val testTransactionId2 = 999

  val pat1 = Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate("2000-01-01"), PatientSex("M"))
  val study1 = Study(-1, -1, StudyInstanceUID("stuid1"), StudyDescription("stdesc1"), StudyDate("19990101"), StudyID("stid1"), AccessionNumber("acc1"), PatientAge("12Y"))
  val equipment1 = Equipment(-1, Manufacturer("manu1"), StationName("station1"))
  val for1 = FrameOfReference(-1, FrameOfReferenceUID("frid1"))
  val series1 = Series(-1, -1, -1, -1, SeriesInstanceUID("seuid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"))
  val image1 = Image(-1, -1, SOPInstanceUID("souid1"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image2 = Image(-1, -1, SOPInstanceUID("souid2"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image3 = Image(-1, -1, SOPInstanceUID("souid3"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  var imageFile1 = ImageFile(-1, FileName("file1"), SourceType.API, None)
  var imageFile2 = ImageFile(-1, FileName("file2"), SourceType.API, None)
  var imageFile3 = ImageFile(-1, FileName("file3"), SourceType.API, None)

  db.withSession { implicit session =>
    val dbPat = dicomMetaDataDao.insert(pat1)
    val dbStudy = dicomMetaDataDao.insert(study1.copy(patientId = dbPat.id))
    val dbEquipment = dicomMetaDataDao.insert(equipment1)
    val dbFor = dicomMetaDataDao.insert(for1)
    val dbSeries = dicomMetaDataDao.insert(series1.copy(studyId = dbStudy.id, equipmentId = dbEquipment.id, frameOfReferenceId = dbFor.id))
    val dbImage1 = dicomMetaDataDao.insert(image1.copy(seriesId = dbSeries.id))
    val dbImage2 = dicomMetaDataDao.insert(image2.copy(seriesId = dbSeries.id))
    val dbImage3 = dicomMetaDataDao.insert(image3.copy(seriesId = dbSeries.id))
    imageFile1 = dicomPropertiesDao.insert(imageFile1.copy(id = dbImage1.id))
    imageFile2 = dicomPropertiesDao.insert(imageFile2.copy(id = dbImage2.id))
    imageFile3 = dicomPropertiesDao.insert(imageFile3.copy(id = dbImage3.id))
  }

  val capturedFileSendRequests = ArrayBuffer.empty[HttpRequest]
  val sendFailedResponseSequenceNumbers = ArrayBuffer.empty[Int]

  val okResponse = HttpResponse()
  val failResponse = HttpResponse(InternalServerError)

  val boxPushActorRef = _system.actorOf(Props(new BoxPushActor(testBox, dbProps, storage, 1000.hours) {

    override def sendFilePipeline = {
      (req: HttpRequest) =>
        {
          capturedFileSendRequests += req
          Future {
            if (sendFailedResponseSequenceNumbers.contains(capturedFileSendRequests.size))
              failResponse
            else
              okResponse
          }
        }
    }

    override def pushImagePipeline(outboxEntry: OutboxEntry, fileName: String, tagValues: Seq[TransactionTagValue]): Future[HttpResponse] = {
      sendFilePipeline(Post(s"${testBox.baseUrl}/image/${outboxEntry.transactionId}/${outboxEntry.sequenceNumber}/${outboxEntry.totalImageCount}", HttpData(Array.empty[Byte])))
    }

  }))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }

  override def beforeEach() {
    capturedFileSendRequests.clear()

    db.withSession { implicit session =>
      boxDao.listOutboxEntries.foreach(outboxEntry =>
        boxDao.removeOutboxEntry(outboxEntry.id))
      boxDao.listTransactionTagValues.foreach(entry =>
        boxDao.removeTransactionTagValue(entry.id))
    }
  }

  "A BoxPushActor" should {

    "remove processed outbox entry" in {

      db.withSession { implicit session =>
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 1, 1, imageFile1.id, false))

        boxPushActorRef ! PollOutbox

        expectNoMsg()

        boxDao.listOutboxEntries.size should be(0)
      }
    }

    "should post file to correct URL" in {

      val outboxEntry = OutboxEntry(1, testBox.id, testTransactionId, 2, 5, imageFile1.id, false)
      db.withSession { implicit session =>
        boxDao.insertOutboxEntry(outboxEntry)
      }

      boxPushActorRef ! PollOutbox

      expectNoMsg()

      capturedFileSendRequests.size should be(1)
      capturedFileSendRequests(0).uri.toString() should be(s"${testBox.baseUrl}/image/${outboxEntry.transactionId}/${outboxEntry.sequenceNumber}/${outboxEntry.totalImageCount}")
    }

    "should post file in correct order" in {

      val outboxEntrySeq1 = OutboxEntry(1, testBox.id, testTransactionId, 1, 2, imageFile1.id, false)
      val outboxEntrySeq2 = OutboxEntry(1, testBox.id, testTransactionId, 2, 2, imageFile2.id, false)
      db.withSession { implicit session =>
        // Insert outbox entries out of order
        boxDao.insertOutboxEntry(outboxEntrySeq2)
        boxDao.insertOutboxEntry(outboxEntrySeq1)
      }

      boxPushActorRef ! PollOutbox

      expectNoMsg()

      capturedFileSendRequests.size should be(2)
      capturedFileSendRequests(0).uri.toString() should be(s"${testBox.baseUrl}/image/${outboxEntrySeq1.transactionId}/${outboxEntrySeq1.sequenceNumber}/${outboxEntrySeq1.totalImageCount}")
      capturedFileSendRequests(1).uri.toString() should be(s"${testBox.baseUrl}/image/${outboxEntrySeq2.transactionId}/${outboxEntrySeq2.sequenceNumber}/${outboxEntrySeq2.totalImageCount}")
    }

    "mark outbox entry as failed when file send fails" in {

      db.withSession { implicit session =>
        val invalidImageId = 666
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 1, 1, invalidImageId, false))

        boxPushActorRef ! PollOutbox

        expectNoMsg()

        val outboxEntries = boxDao.listOutboxEntries
        outboxEntries.size should be(1)
        outboxEntries.foreach(outboxEntry => {
          outboxEntry.failed should be(true)
        })
      }
    }

    "mark all outbox entries for transaction as failed when file send fails" in {

      db.withSession { implicit session =>
        val invalidImageId = 666
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 1, 1, invalidImageId, false))
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 2, 2, invalidImageId, false))

        boxPushActorRef ! PollOutbox
        boxPushActorRef ! PollOutbox
        expectNoMsg()
        expectNoMsg()

        val outboxEntries = boxDao.listOutboxEntries
        outboxEntries.size should be(2)
        outboxEntries.foreach(outboxEntry => {
          outboxEntry.failed should be(true)
        })
      }
    }

    "not mark wrong outbox entry as failed when transaction id is not unique" in {

      db.withSession { implicit session =>
        val invalidImageId = 666
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 1, 1, invalidImageId, false))
        val secondOutboxEntry = boxDao.insertOutboxEntry(OutboxEntry(1, 999, testTransactionId, 1, 1, imageFile1.id, false))

        boxPushActorRef ! PollOutbox
        expectNoMsg()

        val outboxEntries = boxDao.listOutboxEntries
        outboxEntries.size should be(2)
        outboxEntries.foreach(outboxEntry => {
          if (outboxEntry.id == secondOutboxEntry.id)
            outboxEntry.failed should be(false)
        })
      }
    }

    "process other transactions when file send fails" in {

      db.withSession { implicit session =>
        val invalidImageId = 666
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 1, 1, invalidImageId, false))

        boxPushActorRef ! PollOutbox
        expectNoMsg()

        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId2, 1, 1, imageFile2.id, false))

        boxPushActorRef ! PollOutbox
        expectNoMsg()

        capturedFileSendRequests.size should be(1)

        val outboxEntries = boxDao.listOutboxEntries
        outboxEntries.size should be(1)
        outboxEntries.foreach(outboxEntry => {
          outboxEntry.transactionId should be(testTransactionId)
          outboxEntry.failed should be(true)
        })
      }
    }

    "pause outbox processing when remote server is not working, and resumed once remote server is back up" in {

      db.withSession { implicit session =>
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 1, 3, imageFile1.id, false))
        boxDao.insertOutboxEntry(OutboxEntry(2, testBox.id, testTransactionId, 2, 3, imageFile2.id, false))
        boxDao.insertOutboxEntry(OutboxEntry(3, testBox.id, testTransactionId, 3, 3, imageFile3.id, false))

        val n = capturedFileSendRequests.size
        sendFailedResponseSequenceNumbers ++= Seq(n + 2, n + 3, n + 4, n + 5, n + 6)

        boxPushActorRef ! PollOutbox
        expectNoMsg()

        boxDao.listOutboxEntries.size should be(2)

        // remote server is now down

        boxPushActorRef ! PollOutbox
        boxPushActorRef ! PollOutbox
        boxPushActorRef ! PollOutbox
        expectNoMsg()
        expectNoMsg()
        expectNoMsg()

        boxDao.listOutboxEntries.size should be(2)

        // server back up
        sendFailedResponseSequenceNumbers.clear

        boxPushActorRef ! PollOutbox
        boxPushActorRef ! PollOutbox
        expectNoMsg()
        expectNoMsg()

        boxDao.listOutboxEntries.size should be(0)
      }
    }
  }
}