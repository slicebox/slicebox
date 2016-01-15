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

    //    "remove processed outgoing transaction" in {
    //
    //      db.withSession { implicit session =>
    //        val transaction = boxDao.insertOutgoingTransaction(OutgoingTransaction(1, testBox.id, testBox.name, testTransactionId, 0, 1, dbImage1.id, TransactionStatus.WAITING))
    //        val image = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, 1, false))
    //            
    //        boxPushActorRef ! PollOutgoing
    //
    //        expectNoMsg()
    //
    //        boxDao.listOutgoingImages.size should be(0)
    //      }
    //    }

    "should post file to correct URL" in {

      val (transaction, image) =
        db.withSession { implicit session =>
          val transaction = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 1, 1000, TransactionStatus.WAITING))
          val image = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage1.id, false))
          (transaction, image)
        }

      boxPushActorRef ! PollOutgoing

      expectNoMsg()

      capturedFileSendRequests.size should be(1)
      capturedFileSendRequests(0).uri.toString() should be(s"${testBox.baseUrl}/image?transactionid=${transaction.id}&totalimagecount=${transaction.totalImageCount}")
    }

    "should mark outgoing transaction as finished when all files have been sent" in {
      db.withSession { implicit session =>

        val transaction = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 2, 1000, TransactionStatus.WAITING))
        val image1 = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage1.id, false))
        val image2 = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage2.id, false))

        boxDao.listOutgoingTransactions.head.status shouldBe TransactionStatus.WAITING
        
        boxPushActorRef ! PollOutgoing 
        
        expectNoMsg() // both images will be sent
             
        boxDao.listOutgoingTransactions.head.status shouldBe TransactionStatus.FINISHED
      }
    }
    //    "should post file in correct order" in {
    //
    //      val (transaction, image1, image2) =
    //        db.withSession { implicit session =>
    //          // Insert outgoing entries out of order
    //          val transaction = boxDao.insertOutgoingTransaction(OutgoingTransaction(1, testBox.id, testBox.name, testTransactionId, 2, 5, 1000, TransactionStatus.WAITING))
    //          val image1 = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage1.id, false))
    //          val image2 = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage2.id, false))
    //          (transaction, image1, image2)
    //        }
    //
    //      boxPushActorRef ! PollOutgoing
    //      expectNoMsg()
    //      boxPushActorRef ! PollOutgoing
    //      expectNoMsg()
    //
    //      capturedFileSendRequests.size should be(2)
    //      capturedFileSendRequests(0).uri.toString() should be(s"${testBox.baseUrl}/image?transactionid=${transaction.transactionId}&totalimagecount=${transaction.totalImageCount}")
    //      capturedFileSendRequests(1).uri.toString() should be(s"${testBox.baseUrl}/image?transactionid=${transaction.transactionId}&totalimagecount=${transaction.totalImageCount}")
    //    }

    "mark outgoing transaction as failed when file send fails" in {

      db.withSession { implicit session =>
        val invalidImageId = 666

        val transaction = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 1, 1000, TransactionStatus.WAITING))
        val image = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, invalidImageId, false))

        boxPushActorRef ! PollOutgoing

        expectNoMsg()

        val outgoingTransactions = boxDao.listOutgoingTransactions
        outgoingTransactions.size should be(1)
        outgoingTransactions.foreach(_.status shouldBe TransactionStatus.FAILED)
      }
    }

    //    "mark all outgoing entries for transaction as failed when file send fails" in {
    //
    //      db.withSession { implicit session =>
    //        val invalidImageId = 666
    //        boxDao.insertOutgoingTransaction(OutgoingTransaction(1, testBox.id, testBox.name, testTransactionId, 1, 1, invalidImageId, false))
    //        boxDao.insertOutgoingTransaction(OutgoingTransaction(1, testBox.id, testBox.name, testTransactionId, 2, 2, invalidImageId, false))
    //
    //        boxPushActorRef ! PollOutgoing
    //        boxPushActorRef ! PollOutgoing
    //        expectNoMsg()
    //        expectNoMsg()
    //
    //        val outgoingTransactions = boxDao.listOutgoingTransactions
    //        outgoingTransactions.size should be(2)
    //        outgoingTransactions.foreach(outgoingTransaction => {
    //          outgoingTransaction.failed should be(true)
    //        })
    //      }
    //    }

    "not mark wrong outgoing transaction as failed when transaction id is not unique" in {

      db.withSession { implicit session =>
        val invalidImageId = 666
        val transaction1 = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 1, 1000, TransactionStatus.WAITING))
        val image11 = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction1.id, invalidImageId, false))
        val transaction2 = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 1, 1000, TransactionStatus.WAITING))
        val image21 = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction2.id, dbImage1.id, false))

        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        val outgoingTransactions = boxDao.listOutgoingTransactions
        outgoingTransactions.size should be(2)
        outgoingTransactions.foreach { transaction =>
          if (transaction.id == transaction1.id)
            transaction.status shouldBe TransactionStatus.FAILED
          else
            transaction.status shouldBe TransactionStatus.WAITING
        }
      }
    }

    "process other transactions when file send fails" in {

      db.withSession { implicit session =>
        val invalidImageId = 666

        val transaction1 = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 1, 1000, TransactionStatus.WAITING))
        val image1 = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction1.id, invalidImageId, false))

        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        val transaction2 = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 1, 1000, TransactionStatus.WAITING))
        val image2 = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction2.id, dbImage2.id, false))

        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        capturedFileSendRequests.size should be(1)

        val outgoingTransactions = boxDao.listOutgoingTransactions
        outgoingTransactions.size should be(2)
        outgoingTransactions.foreach(transaction =>
          if (transaction.id == transaction1.id) {
            transaction.status shouldBe TransactionStatus.FAILED
          } else {
            transaction.status shouldBe TransactionStatus.FINISHED
          })
      }
    }

    "pause outgoing processing when remote server is not working, and resume once remote server is back up" in {

      db.withSession { implicit session =>
        val transaction = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 3, 1000, TransactionStatus.WAITING))
        val image1 = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage1.id, false))
        val image2 = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage2.id, false))
        val image3 = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage3.id, false))

        failedResponseSendIndices ++= Seq(2,3)
        
        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        boxDao.listOutgoingImagesForOutgoingTransactionId(transaction.id).filter(_.sent == false).size should be(2)

        // server back up
        failedResponseSendIndices.clear()

        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        boxDao.listOutgoingImagesForOutgoingTransactionId(transaction.id).filter(_.sent == false).size should be(0)
      }
    }
  }
}