package se.nimsa.sbx.box

import akka.Done
import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.StatusCodes.{BadGateway, NoContent, NotFound, OK}
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.{ByteString, Timeout}
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.sbx.anonymization.{AnonymizationDAO, AnonymizationServiceActor}
import se.nimsa.sbx.app.JsonFormats
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.metadata.MetaDataDAO
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, MetaDataAdded}
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.CompressionUtil._
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class BoxPollActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with JsonFormats with PlayJsonSupport {

  def this() = this(ActorSystem("BoxPollActorTestSystem"))

  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(30.seconds)
  implicit val materializer = ActorMaterializer()

  val dbConfig = TestUtil.createTestDb("bopollactortest")
  val db = dbConfig.db

  val boxDao = new BoxDAO(dbConfig)
  val metaDataDao = new MetaDataDAO(dbConfig)
  val anonymizationDao = new AnonymizationDAO(dbConfig)

  await(metaDataDao.create())
  await(anonymizationDao.create())
  await(boxDao.create())

  val remoteBoxBaseUrl = "https://someurl.com"
  val remoteBox = await(boxDao.insertBox(Box(-1, "some remote box", "abc", remoteBoxBaseUrl, BoxSendMethod.PUSH, online = false)))

  val notFoundResponse = HttpResponse(status = NotFound)
  var responseCounter = -1
  val mockHttpResponses: ArrayBuffer[HttpResponse] = ArrayBuffer()
  val capturedRequests: ArrayBuffer[HttpRequest] = ArrayBuffer()

  val metaDataService = system.actorOf(Props(new Actor() {
    def receive = {
      case AddMetaData(_, _) =>
        sender ! MetaDataAdded(null, null, null, Image(12, 22, null, null, null), patientAdded = false, studyAdded = false, seriesAdded = false, imageAdded = true, null)
    }
  }), name = "MetaDataService")
  var storageFails = false
  val storage = new RuntimeStorage() {
    override def fileSink(name: String)(implicit executionContext: ExecutionContext): Sink[ByteString, Future[Done]] =
      if (storageFails)
        Sink.cancelled[ByteString].mapMaterializedValue(_ => Future.failed(new RuntimeException("Could not store data")))
      else
        super.fileSink(name)
  }

  val anonymizationService = system.actorOf(AnonymizationServiceActor.props(anonymizationDao, purgeEmptyAnonymizationKeys = false), name = "AnonymizationService")
  val boxService = system.actorOf(BoxServiceActor.props(boxDao, "http://testhost:1234", storage, 10), name = "BoxService")
  val pollBoxActorRef = system.actorOf(Props(new BoxPollActor(remoteBox, storage, 1.hour, "../BoxService", "../MetaDataService", "../AnonymizationService") {

    override def sliceboxRequest(method: HttpMethod, uri: String, entity: MessageEntity): Future[HttpResponse] = {
      val request = HttpRequest(method = method, uri = uri, entity = entity)
      capturedRequests += request
      responseCounter = responseCounter + 1
      if (responseCounter < mockHttpResponses.size)
        Future.successful(mockHttpResponses(responseCounter))
      else
        Future.successful(notFoundResponse)
    }
  }))

  override def beforeEach() {
    storageFails = false

    capturedRequests.clear()

    mockHttpResponses.clear()
    responseCounter = -1

    await(boxDao.clear())
  }

  override def afterAll = TestKit.shutdownActorSystem(system)

  "A BoxPollActor" should {

    "call correct poll URL" in {
      mockHttpResponses += notFoundResponse

      pollBoxActorRef ! PollIncoming

      expectNoMessage(3.seconds)

      capturedRequests.size should be(1)
      capturedRequests(0).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing/poll")
    }

    "call correct URL for getting remote outgoing file" in {
      val outgoingTransactionId = 999
      val outgoingImageId = 33
      val transaction = OutgoingTransaction(outgoingTransactionId, 987, "some box", 1, 2, 112233, 112233, TransactionStatus.WAITING)
      val image = OutgoingImage(outgoingImageId, outgoingTransactionId, 666, 1, sent = false)
      val transactionImage = OutgoingTransactionImage(transaction, image)

      val entity = Await.result(Marshal(transactionImage).to[MessageEntity], 30.seconds)
      mockHttpResponses += HttpResponse(status = OK, entity = entity)

      pollBoxActorRef ! PollIncoming

      expectNoMessage(3.seconds)

      capturedRequests(1).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing?transactionid=$outgoingTransactionId&imageid=$outgoingImageId")
    }

    "handle remote outgoing file" in {
      val outgoingTransactionId = 999
      val outgoingImageId = 33
      val transaction = OutgoingTransaction(outgoingTransactionId, 987, "some box", 1, 2, 2, 2, TransactionStatus.WAITING)
      val image = OutgoingImage(outgoingImageId, outgoingTransactionId, 666, 1, sent = false)
      val transactionImage = OutgoingTransactionImage(transaction, image)

      val entity = Await.result(Marshal(transactionImage).to[MessageEntity], 30.seconds)
      mockHttpResponses += HttpResponse(status = OK, entity = entity)

      val bytes = compress(TestUtil.testImageByteArray)

      mockHttpResponses += HttpResponse(status = OK, entity = HttpEntity(`application/octet-stream`, bytes))
      mockHttpResponses += HttpResponse(status = OK)

      pollBoxActorRef ! PollIncoming

      expectNoMessage(3.seconds)

      // Check that incoming transaction has been created
      val incomingTransactions = await(boxDao.listIncomingTransactions(0, 10))
      incomingTransactions should have length 1

      incomingTransactions.foreach(incomingTransaction => {
        incomingTransaction.outgoingTransactionId should be(outgoingTransactionId)
        incomingTransaction.boxId should be(remoteBox.id)
        incomingTransaction.receivedImageCount should be(1)
        incomingTransaction.totalImageCount should be(2)
      })

      // Check that poll + get image + done + poll message is sent

      capturedRequests.size should be(4)
      capturedRequests(2).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing/done")
    }

    "go back to polling state when poll request returns 404" in {
      mockHttpResponses += notFoundResponse
      pollBoxActorRef ! PollIncoming
      expectNoMessage(3.seconds)

      mockHttpResponses += notFoundResponse
      pollBoxActorRef ! PollIncoming
      expectNoMessage(3.seconds)

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
      val entity1 = Await.result(Marshal(transactionImage1).to[MessageEntity], 30.seconds)
      mockHttpResponses += HttpResponse(status = OK, entity = entity1)

      mockHttpResponses += HttpResponse(status = OK, entity = HttpEntity(`application/octet-stream`, bytes))
      mockHttpResponses += HttpResponse(NoContent)
      // done reply
      val entity2 = Await.result(Marshal(transactionImage2).to[MessageEntity], 30.seconds)
      mockHttpResponses += HttpResponse(status = OK, entity = entity2)

      mockHttpResponses += HttpResponse(status = OK, entity = HttpEntity(`application/octet-stream`, bytes))
      mockHttpResponses += HttpResponse(NoContent) // done reply

      pollBoxActorRef ! PollIncoming

      expectNoMessage(3.seconds)

      val incomingTransactions = await(boxDao.listIncomingTransactions(0, 10))
      incomingTransactions should have length 1
      incomingTransactions.head.status shouldBe TransactionStatus.FINISHED
    }

    "keep trying to fetch remote file until fetching succeeds" in {
      val outgoingTransactionId = 999
      val transaction = OutgoingTransaction(outgoingTransactionId, 987, "some box", 1, 2, 2, 2, TransactionStatus.WAITING)
      val image = OutgoingImage(456, outgoingTransactionId, 33, 1, sent = false)
      val transactionImage = OutgoingTransactionImage(transaction, image)

      val entity = Await.result(Marshal(transactionImage).to[MessageEntity], 30.seconds)
      mockHttpResponses += HttpResponse(status = OK, entity = entity)
      mockHttpResponses += HttpResponse(status = BadGateway)
      mockHttpResponses += HttpResponse(status = OK, entity = entity)
      mockHttpResponses += HttpResponse(status = BadGateway)
      mockHttpResponses += HttpResponse(status = OK, entity = entity)

      val bytes = compress(TestUtil.testImageByteArray)

      mockHttpResponses += HttpResponse(status = OK, entity = HttpEntity(`application/octet-stream`, bytes))
      mockHttpResponses += HttpResponse(NoContent)

      // poll box, outgoing transaction will be found and an attempt to fetch the file will fail
      pollBoxActorRef ! PollIncoming
      expectNoMessage(3.seconds)

      // poll box again, fetching the file will fail again
      pollBoxActorRef ! PollIncoming
      expectNoMessage(3.seconds)

      // poll box again, fetching the file will succeed, done message will be sent
      pollBoxActorRef ! PollIncoming
      expectNoMessage(3.seconds)

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

      val entity = Await.result(Marshal(transactionImage).to[MessageEntity], 30.seconds)
      mockHttpResponses += HttpResponse(status = OK, entity = entity)

      val bytes = compress(Array[Byte](1, 24, 45, 65, 4, 54, 33, 22))

      mockHttpResponses += HttpResponse(status = OK, entity = HttpEntity(`application/octet-stream`, bytes))
      mockHttpResponses += HttpResponse(status = NoContent)

      // poll box, reading the file will fail, failed message will be sent
      pollBoxActorRef ! PollIncoming
      expectNoMessage(3.seconds)

      // Check that requests are sent as expected
      capturedRequests.size should be(3)
      capturedRequests(2).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing/failed")
    }

    "should tell the box it is pulling images from that a transaction has failed when an image cannot be stored" in {
      storageFails = true

      val outgoingTransactionId = 999
      val transaction = OutgoingTransaction(outgoingTransactionId, 987, "some box", 1, 2, 2, 2, TransactionStatus.WAITING)
      val image = OutgoingImage(456, outgoingTransactionId, 33, 1, sent = false)
      val transactionImage = OutgoingTransactionImage(transaction, image)

      val entity = Await.result(Marshal(transactionImage).to[MessageEntity], 30.seconds)
      mockHttpResponses += HttpResponse(status = OK, entity = entity)

      val bytes = compress(TestUtil.testImageByteArray)

      mockHttpResponses += HttpResponse(status = OK, entity = HttpEntity(`application/octet-stream`, bytes))
      mockHttpResponses += HttpResponse(status = NoContent)

      // poll box, storing the file will fail, failed message will be sent
      pollBoxActorRef ! PollIncoming
      expectNoMessage(3.seconds)

      // Check that requests are sent as expected
      capturedRequests.size should be(3)
      capturedRequests(2).uri.toString() should be(s"$remoteBoxBaseUrl/outgoing/failed")
    }

  }
}