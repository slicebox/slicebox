package se.nimsa.sbx.box

import java.nio.file.Files

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import org.scalatest._
import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.util.Timeout
import se.nimsa.sbx.anonymization.AnonymizationServiceActor
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.metadata.MetaDataDAO
import se.nimsa.sbx.metadata.MetaDataProtocol.GetImage
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

  val testBox = Box(1, "Test Box", "abc123", "testbox.com", BoxSendMethod.PUSH, online = false)

  val (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8)) =
    db.withSession { implicit session =>
      TestUtil.insertMetaData(metaDataDao)
    }

  val capturedFileSendRequests = ArrayBuffer.empty[HttpRequest]
  val failedResponseSendIndices = ArrayBuffer.empty[Int]

  val okResponse = HttpResponse()
  val failResponse = HttpResponse(InternalServerError)

  val metaDataService = system.actorOf(Props(new Actor() {
    var imageFound = true
    def receive = {
      case GetImage(imageId) if imageId < 4 =>
        sender ! Some(Image(imageId, 2, null, null, null))
      case GetImage(_) =>
        sender ! None
    }
  }), name = "MetaDataService")
  val storageService = system.actorOf(Props[MockupStorageActor], name = "StorageService")
  val anonymizationService = system.actorOf(AnonymizationServiceActor.props(dbProps, 5.minutes), name = "AnonymizationService")
  val boxService = system.actorOf(BoxServiceActor.props(dbProps, "http://testhost:1234", 1.minute), name = "BoxService")
  val boxPushActorRef = system.actorOf(Props(new BoxPushActor(testBox, Timeout(30.seconds), 1000.hours, 1000.hours, "../BoxService", "../MetaDataService", "../StorageService", "../AnonymizationService") {

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

    }), name = "PushBox")
  
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

    "should post file to correct URL" in {

      val (transaction, image) =
        db.withSession { implicit session =>
          val transaction = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 1, 1000, 1000, TransactionStatus.WAITING))
          val image = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage1.id, 1, sent = false))
          (transaction, image)
        }

      boxPushActorRef ! PollOutgoing

      expectNoMsg()

      capturedFileSendRequests.size should be(1)
      capturedFileSendRequests(0).uri.toString() should be(s"${testBox.baseUrl}/image?transactionid=${transaction.id}&sequencenumber=${image.sequenceNumber}&totalimagecount=${transaction.totalImageCount}")
    }

    "should post file in correct order" in {
      db.withSession { implicit session =>

        // Insert outgoing images out of order
        val transaction = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 2, 1000, 1000, TransactionStatus.WAITING))
        val image1 = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage1.id, 2, sent = false))
        val image2 = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage2.id, 1, sent = false))

        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        capturedFileSendRequests.size should be(2)
        capturedFileSendRequests(0).uri.toString() should be(s"${testBox.baseUrl}/image?transactionid=${transaction.id}&sequencenumber=${image2.sequenceNumber}&totalimagecount=${transaction.totalImageCount}")
        capturedFileSendRequests(1).uri.toString() should be(s"${testBox.baseUrl}/image?transactionid=${transaction.id}&sequencenumber=${image1.sequenceNumber}&totalimagecount=${transaction.totalImageCount}")
      }
    }

    "should mark outgoing transaction as finished when all files have been sent" in {
      db.withSession { implicit session =>

        val transaction = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 2, 1000, 1000, TransactionStatus.WAITING))
        boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage1.id, 1, sent = false))
        boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage2.id, 2, sent = false))

        boxDao.listOutgoingTransactions.head.status shouldBe TransactionStatus.WAITING

        boxPushActorRef ! PollOutgoing

        expectNoMsg() // both images will be sent

        boxDao.listOutgoingTransactions.head.status shouldBe TransactionStatus.FINISHED
      }
    }

    "mark outgoing transaction as failed when file send fails" in {

      db.withSession { implicit session =>
        val invalidImageId = 666

        val transaction = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 1, 1000, 1000, TransactionStatus.WAITING))
        val image = boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, invalidImageId, 1, sent = false))

        boxPushActorRef ! PollOutgoing

        expectNoMsg()

        val outgoingTransactions = boxDao.listOutgoingTransactions
        outgoingTransactions.size should be(1)
        outgoingTransactions.foreach(_.status shouldBe TransactionStatus.FAILED)
      }
    }

    "not mark wrong outgoing transaction as failed when transaction id is not unique" in {

      db.withSession { implicit session =>
        val invalidImageId = 666
        val transaction1 = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 1, 1000, 1000, TransactionStatus.WAITING))
        boxDao.insertOutgoingImage(OutgoingImage(-1, transaction1.id, invalidImageId, 1, sent = false))
        val transaction2 = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 1, 1000, 1000, TransactionStatus.WAITING))
        boxDao.insertOutgoingImage(OutgoingImage(-1, transaction2.id, dbImage1.id, 1, sent = false))

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

        val transaction1 = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 1, 1000, 1000, TransactionStatus.WAITING))
        boxDao.insertOutgoingImage(OutgoingImage(-1, transaction1.id, invalidImageId, 1, sent = false))

        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        val transaction2 = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 1, 1000, 1000, TransactionStatus.WAITING))
        boxDao.insertOutgoingImage(OutgoingImage(-1, transaction2.id, dbImage2.id, 1, sent = false))

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
        val transaction = boxDao.insertOutgoingTransaction(OutgoingTransaction(-1, testBox.id, testBox.name, 0, 3, 1000, 1000, TransactionStatus.WAITING))
        boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage1.id, 1, sent = false))
        boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage2.id, 2, sent = false))
        boxDao.insertOutgoingImage(OutgoingImage(-1, transaction.id, dbImage3.id, 3, sent = false))

        failedResponseSendIndices ++= Seq(2, 3)

        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        boxDao.listOutgoingImagesForOutgoingTransactionId(transaction.id).count(_.sent == false) should be(2)

        // server back up
        failedResponseSendIndices.clear()

        boxPushActorRef ! PollOutgoing
        expectNoMsg()

        boxDao.listOutgoingImagesForOutgoingTransactionId(transaction.id).count(_.sent == false) should be(0)
      }
    }
  }
}