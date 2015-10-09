package se.nimsa.sbx.box

import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterEach
import akka.testkit.ImplicitSender
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import java.nio.file.Files
import scala.slick.driver.H2Driver
import se.nimsa.sbx.app.DbProps
import scala.slick.jdbc.JdbcBackend.Database
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.storage.StorageProtocol.DatasetReceived
import se.nimsa.sbx.util.TestUtil
import akka.actor.Actor
import akka.actor.Props
import spray.http.HttpResponse
import spray.http.HttpRequest
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import spray.http.StatusCodes._
import scala.concurrent.duration.DurationInt
import se.nimsa.sbx.app.JsonFormats
import spray.httpx.marshalling._
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.http.HttpEntity
import spray.http.StatusCodes
import spray.http.ContentTypes
import java.nio.file.Paths
import spray.http.HttpData
import akka.actor.ReceiveTimeout
import se.nimsa.sbx.anonymization.AnonymizationServiceActor
import akka.util.Timeout
import se.nimsa.sbx.storage.StorageProtocol.GetDataset
import se.nimsa.sbx.box.MockupStorageActor.ShowGoodBehavior
import se.nimsa.sbx.box.MockupStorageActor.ShowBadBehavior
import se.nimsa.sbx.util.CompressionUtil._

class BoxPollActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with JsonFormats {

  def this() = this(ActorSystem("BoxPollActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:boxpollactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val storage = Files.createTempDirectory("slicebox-test-storage-")

  val boxDao = new BoxDAO(H2Driver)

  db.withSession { implicit session =>
    boxDao.create
  }

  val remoteBoxBaseUrl = "https://someurl.com"
  val remoteBox =
    db.withSession { implicit session =>
      boxDao.insertBox(Box(-1, "some remote box", "abc", remoteBoxBaseUrl, BoxSendMethod.PUSH, false))
    }
  val transferData = BoxTransferData(remoteBox.id, "secret")
  db.withSession { implicit session =>
    boxDao.insertBoxTransferData(transferData)
  }

  val notFoundResponse = HttpResponse(NotFound)
  var responseCounter = -1
  val mockHttpResponses: ArrayBuffer[HttpResponse] = ArrayBuffer()
  val capturedRequests: ArrayBuffer[HttpRequest] = ArrayBuffer()

  val storageService = system.actorOf(Props[MockupStorageActor], name = "StorageService")
  val anonymizationService = system.actorOf(AnonymizationServiceActor.props(dbProps), name = "AnonymizationService")
  val pollBoxActorRef = system.actorOf(Props(new BoxPollActor(remoteBox, transferData, dbProps, Timeout(30.seconds), 1.hour, 1000.hours, "../StorageService", "../AnonymizationService") {

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
    TestUtil.deleteFolder(storage)
  }

  "A BoxPollActor" should {

    "call correct poll URL" in {
      mockHttpResponses += notFoundResponse

      pollBoxActorRef ! PollRemoteBox

      expectNoMsg

      capturedRequests.size should be(1)
      capturedRequests(0).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
    }

    "call correct URL for getting remote outbox file" in {
      val transactionId = 999
      val outboxEntry = OutboxEntry(123, 987, "some box", transactionId, 1, 2, 112233, false)

      marshal(outboxEntry) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }

      pollBoxActorRef ! PollRemoteBox

      expectNoMsg

      capturedRequests(1).uri.toString() should be(s"$remoteBoxBaseUrl/outbox?transactionid=$transactionId&sequencenumber=1")
    }

    "handle remote outbox file" in {
      val transactionId = 999
      val outboxEntry = OutboxEntry(123, 987, "some box", transactionId, 1, 2, 2, false)

      marshal(outboxEntry) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }

      val bytes = compress(TestUtil.testImageByteArray)

      mockHttpResponses += HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
      mockHttpResponses += HttpResponse(StatusCodes.OK)

      pollBoxActorRef ! PollRemoteBox

      expectNoMsg

      // Check that inbox entry has been created
      db.withSession { implicit session =>
        val inboxEntries = boxDao.listInboxEntries
        inboxEntries.size should be(1)

        inboxEntries.foreach(inboxEntry => {
          inboxEntry.transactionId should be(transactionId)
          inboxEntry.remoteBoxId should be(remoteBox.id)
          inboxEntry.receivedImageCount should be(1)
          inboxEntry.totalImageCount should be(2)
        })
      }

      // Check that poll + get image + done + poll message is sent

      capturedRequests.size should be(4)
      capturedRequests(2).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/done")
    }

    "go back to polling state when poll request returns 404" in {
      mockHttpResponses += notFoundResponse
      pollBoxActorRef ! PollRemoteBox
      expectNoMsg

      mockHttpResponses += notFoundResponse
      pollBoxActorRef ! PollRemoteBox
      expectNoMsg

      capturedRequests.size should be(2)
      capturedRequests(0).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
      capturedRequests(1).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
    }

    "go back to polling state if a step in the polling sequence exceeds the PollBoxActor's timeout limit" in {

      pollBoxActorRef ! PollRemoteBox
      pollBoxActorRef ! ReceiveTimeout // waiting for remote server timeout triggers this message 

      // Check that no inbox entry was created since the poll request timed out
      db.withSession { implicit session =>
        val inboxEntries = boxDao.listInboxEntries
        inboxEntries.size should be(0)
      }

      pollBoxActorRef ! PollRemoteBox
      expectNoMsg

      // make sure we are back in the polling state. If we are, there should be two polling requests
      capturedRequests.size should be(2)
      capturedRequests(0).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
      capturedRequests(1).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
    }

    "keep trying to fetch remote file until fetching succeeds" in {
      val transactionId = 999
      val outboxEntry = OutboxEntry(123, 987, "some box", transactionId, 1, 2, 2, false)

      marshal(outboxEntry) match {
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

      // poll box, outbox entry will be found and an attempt to fetch the file will fail
      pollBoxActorRef ! PollRemoteBox
      expectNoMsg

      // poll box again, fetching the file will fail again
      pollBoxActorRef ! PollRemoteBox
      expectNoMsg

      // poll box again, fetching the file will succeed, done message will be sent
      pollBoxActorRef ! PollRemoteBox
      expectNoMsg

      // Check that requests are sent as expected
      capturedRequests.size should be(8)
      capturedRequests(6).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/done")
      capturedRequests(7).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
    }

    "should tell the box it is pulling images from that a transaction has failed due to receiving an invalid DICOM file" in {
      val transactionId = 999
      val outboxEntry = OutboxEntry(123, 987, "some box", transactionId, 1, 2, 2, false)

      marshal(outboxEntry) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }

      val bytes = compress(Array[Byte](1, 24, 45, 65, 4, 54, 33, 22))

      mockHttpResponses += HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
      mockHttpResponses += HttpResponse(StatusCodes.NoContent)

      // poll box, reading the file will fail, failed message will be sent
      pollBoxActorRef ! PollRemoteBox
      expectNoMsg

      // Check that requests are sent as expected
      capturedRequests.size should be(3)
      capturedRequests(2).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/failed")
    }

    "should tell the box it is pulling images from that a transaction has failed when an image cannot be stored" in {
      storageService ! ShowBadBehavior(new IllegalArgumentException("Pretending I cannot store dataset."))

      val transactionId = 999
      val outboxEntry = OutboxEntry(123, 987, "some box", transactionId, 1, 2, 2, false)

      marshal(outboxEntry) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }

      val bytes = compress(TestUtil.testImageByteArray)

      mockHttpResponses += HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/octet-stream`, HttpData(bytes)))
      mockHttpResponses += HttpResponse(StatusCodes.NoContent)

      // poll box, storing the file will fail, failed message will be sent
      pollBoxActorRef ! PollRemoteBox
      expectNoMsg

      // Check that requests are sent as expected
      capturedRequests.size should be(3)
      capturedRequests(2).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/failed")
    }

  }
}