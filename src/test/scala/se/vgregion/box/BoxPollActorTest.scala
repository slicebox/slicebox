package se.vgregion.box

import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterEach
import akka.testkit.ImplicitSender
import org.scalatest.BeforeAndAfterAll
import akka.actor.ActorSystem
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import java.nio.file.Files
import scala.slick.driver.H2Driver
import se.vgregion.app.DbProps
import scala.slick.jdbc.JdbcBackend.Database
import se.vgregion.box.BoxProtocol._
import se.vgregion.dicom.DicomProtocol.DatasetReceived
import se.vgregion.util.TestUtil
import akka.actor.Props
import spray.http.HttpResponse
import spray.http.HttpRequest
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import spray.http.StatusCodes._
import scala.concurrent.duration.DurationInt
import se.vgregion.app.JsonFormats
import spray.httpx.marshalling._
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.http.HttpEntity
import spray.http.StatusCodes
import spray.http.ContentTypes
import java.nio.file.Paths
import spray.http.HttpData

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
  
  val remoteBoxBaseUrl = "https://someurl.com/"
  var remoteBox: Box = null
  db.withSession { implicit session =>
    remoteBox = boxDao.insertBox(Box(-1, "some remote box", "abc", remoteBoxBaseUrl, BoxSendMethod.PUSH))
  }
  
  val notFoundRespone = HttpResponse(NotFound)
  var responseCounter = -1
  var mockHttpResponses: ArrayBuffer[HttpResponse] = ArrayBuffer()
  val capturedFileSendRequests: ArrayBuffer[HttpRequest] = ArrayBuffer()
  
  val pollBoxActorRef = _system.actorOf(Props(new BoxPollActor(remoteBox, dbProps, 1000.millis) {
    override def sendRequestToRemoteBoxPipeline = {
      (req: HttpRequest) => {
        capturedFileSendRequests += req
        responseCounter = responseCounter + 1
        Future {
          if (responseCounter < mockHttpResponses.size) mockHttpResponses(responseCounter)
          else notFoundRespone
        }
      }
    }
  }))

  
  override def beforeEach() {
    capturedFileSendRequests.clear()
    
    mockHttpResponses.clear()
    responseCounter = -1
    
    db.withSession { implicit session =>
      boxDao.listBoxes.foreach(box => {
        boxDao.removeBox(box.id)
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
      mockHttpResponses += notFoundRespone
      
      Thread.sleep(500)
      
      capturedFileSendRequests.size should be(1)
      capturedFileSendRequests(0).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
    }
    
    "call correct URL for getting remote outbox file" in {
      val transactionId = 999
      val imageId = 112233
      val outboxEntry = OutboxEntry(123, 987, transactionId, 1, 2, imageId, false)
      
      marshal(outboxEntry) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e) => fail(e)
      }
      
      Thread.sleep(900)
      capturedFileSendRequests.size should be(2)
      capturedFileSendRequests(1).uri.toString() should be(s"$remoteBoxBaseUrl/outbox?transactionId=$transactionId&sequenceNumber=1")
    }
    
    "handle remote outbox file" in {
      val transactionId = 999
      
      val outboxEntry = OutboxEntry(123, 987, transactionId, 1, 2, 112233, false)
      
      marshal(outboxEntry) match {
        case Right(entity) => mockHttpResponses += HttpResponse(StatusCodes.OK, entity)
        case Left(e) => fail(e)
      }
      
      val fileName = "anon270.dcm"
      val dcmPath = Paths.get(getClass().getResource(fileName).toURI())
      val dcmFile = dcmPath.toFile
      
      mockHttpResponses += HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/octet-stream`, HttpData(dcmFile)))
      
      Thread.sleep(200)
      
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
    }
    
    "go back to polling state when poll request returns 404" in {
      mockHttpResponses += notFoundRespone
      
      Thread.sleep(2000)
      
      // TODO: not very robust way to test this
      capturedFileSendRequests.size should be(2)
      capturedFileSendRequests(0).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
      capturedFileSendRequests(1).uri.toString() should be(s"$remoteBoxBaseUrl/outbox/poll")
    }
  }
}