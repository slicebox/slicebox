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
import se.nimsa.sbx.dicom.DicomProtocol.DatasetReceived
import se.nimsa.sbx.util.TestUtil
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
import se.nimsa.sbx.box.BoxPollActor.PollRemoteBox

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
  var remoteBox: Box = null
  db.withSession { implicit session =>
    remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", remoteBoxBaseUrl, BoxSendMethod.PUSH, false))
  }

  val notFoundResponse = HttpResponse(NotFound)
  var responseCounter = -1
  val mockHttpResponses: ArrayBuffer[HttpResponse] = ArrayBuffer()
  val capturedRequests: ArrayBuffer[HttpRequest] = ArrayBuffer()
  var withSlowResponse = false

  val pollBoxActorRef = _system.actorOf(Props(new BoxPollActor(remoteBox, dbProps, 1.hour, 500.millis) {
    override def sendRequestToRemoteBoxPipeline = {
      (req: HttpRequest) =>
        {
          capturedRequests += req
          responseCounter = responseCounter + 1
          Future {
            if (withSlowResponse)
              Thread.sleep(1500)
            if (responseCounter < mockHttpResponses.size) mockHttpResponses(responseCounter)
            else notFoundResponse
          }
        }
    }
  }))

  override def beforeEach() {
    capturedRequests.clear()

    mockHttpResponses.clear()
    responseCounter = -1

    withSlowResponse = false

    db.withSession { implicit session =>
      boxDao.listBoxes.foreach(box => {
        boxDao.removeBox(box.id)
      })

      boxDao.listInboxEntries.foreach(inboxEntry => {
        boxDao.removeInboxEntry(inboxEntry.id)
      })

      boxDao.listOutboxEntries.foreach(outboxEntry => {
        boxDao.removeOutboxEntry(outboxEntry.id)
      })
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

      Thread.sleep(1000)

      capturedRequests.size should be(1)
      capturedRequests(0).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
    }

    "call correct URL for getting remote outbox file" in {
      val transactionId = 999
      val outboxEntry = OutboxEntry(123, 987, transactionId, 1, 2, 112233, false)

      marshal(outboxEntry) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }

      pollBoxActorRef ! PollRemoteBox

      Thread.sleep(1000)

      capturedRequests(1).uri.toString() should be(s"$remoteBoxBaseUrl/outbox?transactionid=$transactionId&sequencenumber=1")
    }

    "handle remote outbox file" in {
      val transactionId = 999
      val outboxEntry = OutboxEntry(123, 987, transactionId, 1, 2, 112233, false)

      marshal(outboxEntry) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e)       => fail(e)
      }

      val fileName = "anon270.dcm"
      val dcmPath = Paths.get(getClass().getResource(fileName).toURI())
      val dcmFile = dcmPath.toFile

      mockHttpResponses += HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/octet-stream`, HttpData(dcmFile)))
      mockHttpResponses += HttpResponse(StatusCodes.OK)

      pollBoxActorRef ! PollRemoteBox

      Thread.sleep(1000)

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

      // Check that done message is sent

      capturedRequests.size should be(3)
      capturedRequests(2).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/done")
    }

    "go back to polling state when poll request returns 404" in {
      mockHttpResponses += notFoundResponse
      pollBoxActorRef ! PollRemoteBox
      Thread.sleep(1000)

      mockHttpResponses += notFoundResponse
      pollBoxActorRef ! PollRemoteBox
      Thread.sleep(1000)

      capturedRequests.size should be(2)
      capturedRequests(0).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
      capturedRequests(1).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
    }

    "go back to polling state if a step in the polling sequence exceeds the PollBoxActor's timeout limit" in {
      withSlowResponse = true

      pollBoxActorRef ! PollRemoteBox
      Thread.sleep(2000) // sequence times out after 500 ms, response finally arrives after 750 ms but goes unhandled since we are in the polling state by then

      // Check that no inbox entry was created since the poll request timed out
      db.withSession { implicit session =>
        val inboxEntries = boxDao.listInboxEntries
        inboxEntries.size should be(0)
      }

      withSlowResponse = false

      pollBoxActorRef ! PollRemoteBox
      Thread.sleep(1000)
      
      // make sure we are back in the polling state. If we are, there should be two polling requests
      capturedRequests.size should be(2)
      capturedRequests(0).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
      capturedRequests(1).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
    }

  }
}