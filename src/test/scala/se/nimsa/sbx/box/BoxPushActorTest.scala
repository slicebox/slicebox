package se.nimsa.sbx.box

import java.nio.file.Files

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

import org.scalatest._

import akka.actor.ActorSystem
import akka.actor.Props
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.util.Timeout
import se.nimsa.sbx.anonymization.AnonymizationServiceActor
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.metadata.MetaDataDAO
import se.nimsa.sbx.util.TestUtil
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.StatusCodes.InternalServerError

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

  val (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8)) =
    db.withSession { implicit session =>
      TestUtil.insertMetaData(metaDataDao)
    }

  val capturedFileSendRequests = ArrayBuffer.empty[HttpRequest]
  val failedResponseSendIndices = ArrayBuffer.empty[Int]

  val okResponse = HttpResponse()
  val failResponse = HttpResponse(InternalServerError)

  val storageService = system.actorOf(Props[MockupStorageActor], name = "StorageService")
  val anonymizationService = system.actorOf(AnonymizationServiceActor.props(dbProps, 5.minutes), name = "AnonymizationService")
  val boxPushActorRef = system.actorOf(Props(new BoxPushActor(testBox, dbProps, Timeout(30.seconds), 1000.hours, 1000.hours, "../StorageService", "../AnonymizationService") {

    override def sendFilePipeline = {
      (req: HttpRequest) =>
        {
          capturedFileSendRequests += req
          Future {
            if (failedResponseSendIndices.contains(capturedFileSendRequests.size))
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
    failedResponseSendIndices.clear()
  
    db.withSession { implicit session =>
      boxDao.clear
    }
  }

  "A BoxPushActor" should {

    //    "remove processed outgoing entry" in {
    //
    //      db.withSession { implicit session =>
    //        val entry = boxDao.insertOutgoingEntry(OutgoingEntry(1, testBox.id, testBox.name, testTransactionId, 0, 1, dbImage1.id, TransactionStatus.WAITING))
    //        val image = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, 1, false))
    //            
    //        boxPushActorRef ! PollOutgoing
    //
    //        expectNoMsg()
    //
    //        boxDao.listOutgoingImages.size should be(0)
    //      }
    //    }

    "should post file to correct URL" in {

      val (entry, image) =
        db.withSession { implicit session =>
          val entry = boxDao.insertOutgoingEntry(OutgoingEntry(1, testBox.id, testBox.name, testTransactionId, 0, 1, 1000, TransactionStatus.WAITING))
          val image = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage1.id, false))
          (entry, image)
        }

      boxPushActorRef ! PollOutgoing

      expectNoMsg()

      capturedFileSendRequests.size should be(1)
      capturedFileSendRequests(0).uri.toString() should be(s"${testBox.baseUrl}/transactions/image?transactionid=${entry.transactionId}&totalimagecount=${entry.totalImageCount}")
    }

    "should mark outgoing entry as finished when all files have been sent" in {
      db.withSession { implicit session =>

        val entry = boxDao.insertOutgoingEntry(OutgoingEntry(1, testBox.id, testBox.name, testTransactionId, 0, 2, 1000, TransactionStatus.WAITING))
        val image1 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage1.id, false))
        val image2 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage2.id, false))

        boxDao.listOutgoingEntries.head.status shouldBe TransactionStatus.WAITING
        
        boxPushActorRef ! PollOutgoing 
        
        expectNoMsg() // both images will be sent
             
        boxDao.listOutgoingEntries.head.status shouldBe TransactionStatus.FINISHED
      }
    }
    //    "should post file in correct order" in {
    //
    //      val (entry, image1, image2) =
    //        db.withSession { implicit session =>
    //          // Insert outgoing entries out of order
    //          val entry = boxDao.insertOutgoingEntry(OutgoingEntry(1, testBox.id, testBox.name, testTransactionId, 2, 5, 1000, TransactionStatus.WAITING))
    //          val image1 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage1.id, false))
    //          val image2 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage2.id, false))
    //          (entry, image1, image2)
    //        }
    //
    //      boxPushActorRef ! PollOutgoing
    //      expectNoMsg()
    //      boxPushActorRef ! PollOutgoing
    //      expectNoMsg()
    //
    //      capturedFileSendRequests.size should be(2)
    //      capturedFileSendRequests(0).uri.toString() should be(s"${testBox.baseUrl}/image?transactionid=${entry.transactionId}&totalimagecount=${entry.totalImageCount}")
    //      capturedFileSendRequests(1).uri.toString() should be(s"${testBox.baseUrl}/image?transactionid=${entry.transactionId}&totalimagecount=${entry.totalImageCount}")
    //    }

    "mark outgoing entry as failed when file send fails" in {

      db.withSession { implicit session =>
        val invalidImageId = 666

        val entry = boxDao.insertOutgoingEntry(OutgoingEntry(1, testBox.id, testBox.name, testTransactionId, 0, 1, 1000, TransactionStatus.WAITING))
        val image = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, invalidImageId, false))

        boxPushActorRef ! PollOutgoing

        expectNoMsg()

        val outgoingEntries = boxDao.listOutgoingEntries
        outgoingEntries.size should be(1)
        outgoingEntries.foreach(_.status shouldBe TransactionStatus.FAILED)
      }
    }

    //    "mark all outgoing entries for transaction as failed when file send fails" in {
    //
    //      db.withSession { implicit session =>
    //        val invalidImageId = 666
    //        boxDao.insertOutgoingEntry(OutgoingEntry(1, testBox.id, testBox.name, testTransactionId, 1, 1, invalidImageId, false))
    //        boxDao.insertOutgoingEntry(OutgoingEntry(1, testBox.id, testBox.name, testTransactionId, 2, 2, invalidImageId, false))
    //
    //        boxPushActorRef ! PollOutgoing
    //        boxPushActorRef ! PollOutgoing
    //        expectNoMsg()
    //        expectNoMsg()
    //
    //        val outgoingEntries = boxDao.listOutgoingEntries
    //        outgoingEntries.size should be(2)
    //        outgoingEntries.foreach(outgoingEntry => {
    //          outgoingEntry.failed should be(true)
    //        })
    //      }
    //    }

    "not mark wrong outgoing entry as failed when transaction id is not unique" in {

      db.withSession { implicit session =>
        val invalidImageId = 666
        val entry1 = boxDao.insertOutgoingEntry(OutgoingEntry(1, testBox.id, testBox.name, testTransactionId, 0, 1, 1000, TransactionStatus.WAITING))
        val image11 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry1.id, invalidImageId, false))
        val entry2 = boxDao.insertOutgoingEntry(OutgoingEntry(1, testBox.id, testBox.name, testTransactionId, 0, 1, 1000, TransactionStatus.WAITING))
        val image21 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry2.id, dbImage1.id, false))

        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        val outgoingEntries = boxDao.listOutgoingEntries
        outgoingEntries.size should be(2)
        outgoingEntries.foreach { entry =>
          if (entry.id == entry1.id)
            entry.status shouldBe TransactionStatus.FAILED
          else
            entry.status shouldBe TransactionStatus.WAITING
        }
      }
    }

    "process other transactions when file send fails" in {

      db.withSession { implicit session =>
        val invalidImageId = 666

        val entry1 = boxDao.insertOutgoingEntry(OutgoingEntry(1, testBox.id, testBox.name, testTransactionId, 0, 1, 1000, TransactionStatus.WAITING))
        val image1 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry1.id, invalidImageId, false))

        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        val entry2 = boxDao.insertOutgoingEntry(OutgoingEntry(1, testBox.id, testBox.name, testTransactionId2, 0, 1, 1000, TransactionStatus.WAITING))
        val image2 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry2.id, dbImage2.id, false))

        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        capturedFileSendRequests.size should be(1)

        val outgoingEntries = boxDao.listOutgoingEntries
        outgoingEntries.size should be(2)
        outgoingEntries.foreach(entry =>
          if (entry.id == entry1.id) {
            entry.transactionId should be(testTransactionId)
            entry.status shouldBe TransactionStatus.FAILED
          } else {
            entry.transactionId should be(testTransactionId2)
            entry.status shouldBe TransactionStatus.FINISHED
          })
      }
    }

    "pause outgoing processing when remote server is not working, and resume once remote server is back up" in {

      db.withSession { implicit session =>
        val entry = boxDao.insertOutgoingEntry(OutgoingEntry(-1, testBox.id, testBox.name, testTransactionId, 0, 3, 1000, TransactionStatus.WAITING))
        val image1 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage1.id, false))
        val image2 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage2.id, false))
        val image3 = boxDao.insertOutgoingImage(OutgoingImage(-1, entry.id, dbImage3.id, false))

        failedResponseSendIndices ++= Seq(2,3)
        
        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        boxDao.listOutgoingImagesForOutgoingEntryId(entry.id).filter(_.sent == false).size should be(2)

        // server back up
        failedResponseSendIndices.clear()

        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        boxDao.listOutgoingImagesForOutgoingEntryId(entry.id).filter(_.sent == false).size should be(0)
      }
    }
  }
}