package se.nimsa.sbx.box

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.sbx.anonymization.AnonymizationServiceActor
import se.nimsa.sbx.app.{DbProps, JsonFormats}
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.box.MockupStorageActor.{ShowBadBehavior, ShowGoodBehavior}
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, MetaDataAdded}
import se.nimsa.sbx.util.CompressionUtil._
import se.nimsa.sbx.util.TestUtil
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import spray.http.StatusCodes._
import spray.http._
import spray.httpx.marshalling._
import spray.httpx.SprayJsonSupport._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

class BoxPollActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with JsonFormats {

  def this() = this(ActorSystem("BoxPollActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:boxpollactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val boxDao = new BoxDAO(H2Driver)

  db.withSession { implicit session =>
    boxDao.create
  }

  val remoteBoxBaseUrl = "https://someurl.com"
  val remoteBox =
    db.withSession { implicit session =>
      boxDao.insertBox(Box(-1, "some remote box", "abc", remoteBoxBaseUrl, BoxSendMethod.PUSH, online = false))
    }

  val notFoundResponse = HttpResponse(NotFound)
  var responseCounter = -1
  val mockHttpResponses: ArrayBuffer[HttpResponse] = ArrayBuffer()
  val capturedRequests: ArrayBuffer[HttpRequest] = ArrayBuffer()

  val metaDataService = system.actorOf(Props(new Actor() {
    def receive = {
      case AddMetaData(attributes, source) =>
        sender ! MetaDataAdded(null, null, null, Image(12, 22, null, null, null), patientAdded = false, studyAdded = false, seriesAdded = false, imageAdded = true, null)
    }
  }), name = "MetaDataService")
  val storageService = system.actorOf(Props[MockupStorageActor], name = "StorageService")
  val anonymizationService = system.actorOf(AnonymizationServiceActor.props(dbProps), name = "AnonymizationService")
  val boxService = system.actorOf(BoxServiceActor.props(dbProps, "http://testhost:1234", 1.minute), name = "BoxService")
  val pollBoxActorRef = system.actorOf(Props(new BoxPollActor(remoteBox, 1.hour, 1000.hours, "../BoxService", "../MetaDataService", "../StorageService", "../AnonymizationService") {

    override def sendRequestToRemoteBoxPipeline = {
      (req: HttpRequest) =>
        {
          capturedRequests += req
          responseCounter = responseCounter + 1
          Future {
            if (responseCounter < mockHttpResponses.size) mockHttpResponses(responseCounter)
            else notFoundResponse
          }
        }
    }
  }))

  override def beforeEach() {
    storageService ! ShowGoodBehavior(3)

    capturedRequests.clear()

    mockHttpResponses.clear()
    responseCounter = -1

    db.withSession { implicit session =>
      boxDao.clear
    }
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A BoxPollActor" should {

    "call correct poll URL" in {
      mockHttpResponses += notFoundResponse

      pollBoxActorRef ! PollIncoming

      expectNoMsg

      capturedRequests.size should be(1)
      capturedRequests(0).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing/poll")
    }

    "call correct URL for getting remote outgoing file" in {
      val outgoingTransactionId = 999
      val outgoingImageId = 33
      val transaction = OutgoingTransaction(outgoingTransactionId, 987, "some box", 1, 2, 112233, 112233, TransactionStatus.WAITING)
      val image = OutgoingImage(outgoingImageId, outgoingTransactionId, 666, 1, sent = false)
      val transactionImage = OutgoingTransactionImage(transaction, image)
      
      marshal(transactionImage) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }

      pollBoxActorRef ! PollIncoming

      expectNoMsg

      capturedRequests(1).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing?transactionid=$outgoingTransactionId&imageid=$outgoingImageId")
    }

    "handle remote outgoing file" in {
      val outgoingTransactionId = 999
      val outgoingImageId = 33
      val transaction = OutgoingTransaction(outgoingTransactionId, 987, "some box", 1, 2, 2, 2, TransactionStatus.WAITING)
      val image = OutgoingImage(outgoingImageId, outgoingTransactionId, 666, 1, sent = false)
      val transactionImage = OutgoingTransactionImage(transaction, image)

      marshal(transactionImage) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }

      val bytes = compress(TestUtil.testImageByteArray)

      mockHttpResponses += HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
      mockHttpResponses += HttpResponse(StatusCodes.OK)

      pollBoxActorRef ! PollIncoming

      expectNoMsg

      // Check that incoming transaction has been created
      db.withSession { implicit session =>
        val incomingTransactions = boxDao.listIncomingTransactions(0, 10)
        incomingTransactions should have length 1

        incomingTransactions.foreach(incomingTransaction => {
          incomingTransaction.outgoingTransactionId should be(outgoingTransactionId)
          incomingTransaction.boxId should be(remoteBox.id)
          incomingTransaction.receivedImageCount should be(1)
          incomingTransaction.totalImageCount should be(2)
        })
      }

      // Check that poll + get image + done + poll message is sent

      capturedRequests.size should be(4)
      capturedRequests(2).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing/done")
    }

    "go back to polling state when poll request returns 404" in {
      mockHttpResponses += notFoundResponse
      pollBoxActorRef ! PollIncoming
      expectNoMsg

      mockHttpResponses += notFoundResponse
      pollBoxActorRef ! PollIncoming
      expectNoMsg

      capturedRequests.size should be(2)
      capturedRequests(0).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing/poll")
      capturedRequests(1).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing/poll")
    }

    "mark incoming transaction as finished when all files have been received" in {
      val outgoingTransactionId = 999
      val transaction = OutgoingTransaction(outgoingTransactionId, 987, "some box", 0, 2, 112233, 112233, TransactionStatus.WAITING)
      val image1 = OutgoingImage(1, outgoingTransactionId, 1, 1, sent = false)
      val image2 = OutgoingImage(2, outgoingTransactionId, 2, 2, sent = false)
      val transactionImage1 = OutgoingTransactionImage(transaction.copy(sentImageCount = 1), image1)
      val transactionImage2 = OutgoingTransactionImage(transaction.copy(sentImageCount = 2), image2)
  
      val bytes = compress(TestUtil.testImageByteArray)
      
      // insert mock responses for fetching two images
      marshal(transactionImage1) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }
      mockHttpResponses += HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
      mockHttpResponses += HttpResponse(StatusCodes.NoContent) // done reply
      marshal(transactionImage2) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }
      mockHttpResponses += HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
      mockHttpResponses += HttpResponse(StatusCodes.NoContent) // done reply

      pollBoxActorRef ! PollIncoming

      expectNoMsg

      db.withSession { implicit session =>
        val incomingTransactions = boxDao.listIncomingTransactions(0, 10)
        incomingTransactions should have length 1
        incomingTransactions.head.status shouldBe TransactionStatus.FINISHED
      }
    }
    
    "mark incoming transaction as failed if the number of received files does not match the number of images in the transaction (the highest sequence number)" in {
      val outgoingTransactionId = 999
      val transaction = OutgoingTransaction(outgoingTransactionId, 987, "some box", 0, 3, 112233, 112233, TransactionStatus.WAITING)
      val image1 = OutgoingImage(1, outgoingTransactionId, 1, 1, sent = false)
      val image2 = OutgoingImage(3, outgoingTransactionId, 2, 3, sent = false)
      val transactionImage1 = OutgoingTransactionImage(transaction.copy(sentImageCount = 1), image1)
      val transactionImage2 = OutgoingTransactionImage(transaction.copy(sentImageCount = 3), image2)
  
      val bytes = compress(TestUtil.testImageByteArray)
      
      // insert mock responses for fetching two images
      marshal(transactionImage1) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }
      mockHttpResponses += HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
      mockHttpResponses += HttpResponse(StatusCodes.NoContent) // done reply
      marshal(transactionImage2) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }
      mockHttpResponses += HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
      mockHttpResponses += HttpResponse(StatusCodes.NoContent) // done reply

      pollBoxActorRef ! PollIncoming

      expectNoMsg

      db.withSession { implicit session =>
        val incomingTransactions = boxDao.listIncomingTransactions(0, 10)
        incomingTransactions should have length 1
        incomingTransactions.head.status shouldBe TransactionStatus.FAILED
      }
    }
    
    "keep trying to fetch remote file until fetching succeeds" in {
      val outgoingTransactionId = 999
      val transaction = OutgoingTransaction(outgoingTransactionId, 987, "some box", 1, 2, 2, 2, TransactionStatus.WAITING)
      val image = OutgoingImage(456, outgoingTransactionId, 33, 1, sent = false)
      val transactionImage = OutgoingTransactionImage(transaction, image)

      marshal(transactionImage) match {
        case Right(entity) =>
          mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
          mockHttpResponses += HttpResponse(StatusCodes.BadGateway)
          mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
          mockHttpResponses += HttpResponse(StatusCodes.BadGateway)
          mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e) => fail(e)
      }

      val bytes = compress(TestUtil.testImageByteArray)

      mockHttpResponses += HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
      mockHttpResponses += HttpResponse(StatusCodes.NoContent)

      // poll box, outgoing transaction will be found and an attempt to fetch the file will fail
      pollBoxActorRef ! PollIncoming
      expectNoMsg

      // poll box again, fetching the file will fail again
      pollBoxActorRef ! PollIncoming
      expectNoMsg

      // poll box again, fetching the file will succeed, done message will be sent
      pollBoxActorRef ! PollIncoming
      expectNoMsg

      // Check that requests are sent as expected
      capturedRequests.size should be(8)
      capturedRequests(6).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing/done")
      capturedRequests(7).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing/poll")
    }

    "should tell the box it is pulling images from that a transaction has failed due to receiving an invalid DICOM file" in {
      val outgoingTransactionId = 999
      val transaction = OutgoingTransaction(outgoingTransactionId, 987, "some box", 1, 2, 2, 2, TransactionStatus.WAITING)
      val image = OutgoingImage(456, outgoingTransactionId, 33, 1, sent = false)
      val transactionImage = OutgoingTransactionImage(transaction, image)

      marshal(transactionImage) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }

      val bytes = compress(Array[Byte](1, 24, 45, 65, 4, 54, 33, 22))

      mockHttpResponses += HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
      mockHttpResponses += HttpResponse(StatusCodes.NoContent)

      // poll box, reading the file will fail, failed message will be sent
      pollBoxActorRef ! PollIncoming
      expectNoMsg

      // Check that requests are sent as expected
      capturedRequests.size should be(3)
      capturedRequests(2).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing/failed")
    }

    "should tell the box it is pulling images from that a transaction has failed when an image cannot be stored" in {
      storageService ! ShowBadBehavior(new IllegalArgumentException("Pretending I cannot store dicom data."))

      val outgoingTransactionId = 999
      val transaction = OutgoingTransaction(outgoingTransactionId, 987, "some box", 1, 2, 2, 2, TransactionStatus.WAITING)
      val image = OutgoingImage(456, outgoingTransactionId, 33, 1, sent = false)
      val transactionImage = OutgoingTransactionImage(transaction, image)

      marshal(transactionImage) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }

      val bytes = compress(TestUtil.testImageByteArray)

      mockHttpResponses += HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
      mockHttpResponses += HttpResponse(StatusCodes.NoContent)

      // poll box, storing the file will fail, failed message will be sent
      pollBoxActorRef ! PollIncoming
      expectNoMsg

      // Check that requests are sent as expected
      capturedRequests.size should be(3)
      capturedRequests(2).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing/failed")
    }

  }
}