package se.nimsa.sbx.box

import akka.testkit.TestKit
import akka.testkit.ImplicitSender
import org.scalatest._
import akka.actor.Actor
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
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.storage.MetaDataDAO
import se.nimsa.sbx.storage.PropertiesDAO
import spray.http.StatusCodes._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import se.nimsa.sbx.storage.StorageServiceActor
import se.nimsa.sbx.anonymization.AnonymizationServiceActor
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import akka.util.Timeout

class BoxPushActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("BoxPushActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:boxpushactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val storage = Files.createTempDirectory("slicebox-test-storage-")

  val boxDao = new BoxDAO(H2Driver)
  val metaDataDao = new MetaDataDAO(H2Driver)

  db.withSession { implicit session =>
    boxDao.create
    metaDataDao.create
  }

  val testBox = Box(1, "Test Box", "abc123", "testbox.com", BoxSendMethod.PUSH, false)

  val testTransactionId = 888
  val testTransactionId2 = 999

  val (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbEquipment1, dbEquipment2, dbEquipment3), (dbFor1, dbFor2), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8)) =
    db.withSession { implicit session =>
      TestUtil.insertMetaData(metaDataDao)
    }

  val capturedFileSendRequests = ArrayBuffer.empty[HttpRequest]
  val sendFailedResponseSequenceNumbers = ArrayBuffer.empty[Int]

  val okResponse = HttpResponse()
  val failResponse = HttpResponse(InternalServerError)

  val storageService = system.actorOf(Props[MockupStorageActor], name = "StorageService")
  val anonymizationService = system.actorOf(AnonymizationServiceActor.props(dbProps), name = "AnonymizationService")
  val boxPushActorRef = system.actorOf(Props(new BoxPushActor(testBox, dbProps, storage, Timeout(30.seconds), 1000.hours, 1000.hours, "../StorageService", "../AnonymizationService") {

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

  }))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }

  override def beforeEach() {
    capturedFileSendRequests.clear()

    db.withSession { implicit session =>
      boxDao.clear
    }
  }

  "A BoxPushActor" should {

    "remove processed outbox entry" in {

      db.withSession { implicit session =>
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 1, 1, dbImage1.id, false))

        boxPushActorRef ! PollOutbox

        expectNoMsg()

        boxDao.listOutboxEntries.size should be(0)
      }
    }

    "should post file to correct URL" in {

      val outboxEntry = OutboxEntry(1, testBox.id, testTransactionId, 2, 5, dbImage1.id, false)
      db.withSession { implicit session =>
        boxDao.insertOutboxEntry(outboxEntry)
      }

      boxPushActorRef ! PollOutbox

      expectNoMsg()

      capturedFileSendRequests.size should be(1)
      capturedFileSendRequests(0).uri.toString() should be(s"${testBox.baseUrl}/image?transactionid=${outboxEntry.transactionId}&sequencenumber=${outboxEntry.sequenceNumber}&totalimagecount=${outboxEntry.totalImageCount}")
    }

    "should post file in correct order" in {

      val outboxEntrySeq1 = OutboxEntry(1, testBox.id, testTransactionId, 1, 2, dbImage1.id, false)
      val outboxEntrySeq2 = OutboxEntry(1, testBox.id, testTransactionId, 2, 2, dbImage2.id, false)
      db.withSession { implicit session =>
        // Insert outbox entries out of order
        boxDao.insertOutboxEntry(outboxEntrySeq2)
        boxDao.insertOutboxEntry(outboxEntrySeq1)
      }

      boxPushActorRef ! PollOutbox
      expectNoMsg()
      boxPushActorRef ! PollOutbox
      expectNoMsg()

      capturedFileSendRequests.size should be(2)
      capturedFileSendRequests(0).uri.toString() should be(s"${testBox.baseUrl}/image?transactionid=${outboxEntrySeq1.transactionId}&sequencenumber=${outboxEntrySeq1.sequenceNumber}&totalimagecount=${outboxEntrySeq1.totalImageCount}")
      capturedFileSendRequests(1).uri.toString() should be(s"${testBox.baseUrl}/image?transactionid=${outboxEntrySeq2.transactionId}&sequencenumber=${outboxEntrySeq2.sequenceNumber}&totalimagecount=${outboxEntrySeq2.totalImageCount}")
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
        val secondOutboxEntry = boxDao.insertOutboxEntry(OutboxEntry(1, 999, testTransactionId, 1, 1, dbImage1.id, false))

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

        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId2, 1, 1, dbImage2.id, false))

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

    "pause outbox processing when remote server is not working, and resume once remote server is back up" in {

      db.withSession { implicit session =>
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 1, 3, dbImage1.id, false))
        boxDao.insertOutboxEntry(OutboxEntry(2, testBox.id, testTransactionId, 2, 3, dbImage2.id, false))
        boxDao.insertOutboxEntry(OutboxEntry(3, testBox.id, testTransactionId, 3, 3, dbImage3.id, false))

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