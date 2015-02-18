package se.vgregion.app

import java.nio.file.Paths
import java.util.UUID
import spray.http.BodyPart
import spray.http.MultipartFormData
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.httpx.marshalling.marshal
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.vgregion.box.BoxProtocol._
import java.io.File
import se.vgregion.dicom.DicomUtil
import spray.http.ContentTypes
import spray.httpx.unmarshalling.BasicUnmarshallers.ByteArrayUnmarshaller
import spray.http.HttpData
import scala.math.abs

class BoxRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:boxroutestest;DB_CLOSE_DELAY=-1"

  "The system" should "return a success message when asked to generate a new base url" in {
    Post("/api/box/generatebaseurl", RemoteBoxName("hosp")) ~> routes ~> check {
      status should be(OK)
      responseAs[BoxBaseUrl].value.isEmpty should be(false)
    }
  }

  it should "return a success message when asked to add a remote box" in {
    Post("/api/box/addremotebox", RemoteBox("uni", "http://uni.edu/box/" + UUID.randomUUID())) ~> routes ~> check {
      status should be(OK)
      val box = responseAs[Box]
      box.sendMethod should be(BoxSendMethod.PUSH)
      box.name should be("uni")
    }
  }

  it should "return a bad request message when asked to add a remote box with a malformed base url" in {
    Post("/api/box/addremotebox", RemoteBox("uni2", "")) ~> routes ~> check {
      status should be(BadRequest)
    }
    Post("/api/box/addremotebox", RemoteBox("uni2", "malformed/url")) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return a list of two boxes when listing boxes" in {
    Get("/api/box") ~> routes ~> check {
      val boxes = responseAs[List[Box]]
      boxes.size should be(2)
    }
  }

  it should "support removing a box" in {
    Delete("/api/box/1") ~> routes ~> check {
      status should be(NoContent)
    }
    Delete("/api/box/2") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "be able to receive a pushed image" in {

    // first, add a box on the poll (university) side
    val uniUrl =
      Post("/api/box/generatebaseurl", RemoteBoxName("hosp")) ~> routes ~> check {
        status should be(OK)
        responseAs[BoxBaseUrl]
      }

    // get the token from the url
    val token = uniUrl.value.substring(uniUrl.value.lastIndexOf("/") + 1)

    // then, push an image from the hospital to the uni box we just set up
    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(getClass().getResource(fileName).toURI())
    val dcmFile = dcmPath.toFile

    val testTransactionId = abs(UUID.randomUUID().getMostSignificantBits())
    val sequenceNumber = 1L
    val totalImageCount = 1L
    
    Post(s"/api/box/$token/image/$testTransactionId/$sequenceNumber/$totalImageCount", HttpData(dcmFile)) ~> routes ~> check {
      status should be(NoContent)
    }
  }
  
  it should "return not found when polling empty outbox" in {
    // first, add a box on the poll (university) side
    val uniUrl =
      Post("/api/box/generatebaseurl", RemoteBoxName("hosp2")) ~> routes ~> check {
        status should be(OK)
        responseAs[BoxBaseUrl]
      }

    // get the token from the url
    val token = uniUrl.value.substring(uniUrl.value.lastIndexOf("/") + 1)
    
    Get(s"/api/box/$token/outbox/poll") ~> routes ~> check {
      status should be(NotFound)
    }
  }
  
  it should "return OutboxEntry when polling non empty outbox" in {
    // first, add a box on the poll (university) side
    val uniUrl =
      Post("/api/box/generatebaseurl", RemoteBoxName("hosp3")) ~> routes ~> check {
        status should be(OK)
        responseAs[BoxBaseUrl]
      }

    // get the token from the url
    val token = uniUrl.value.substring(uniUrl.value.lastIndexOf("/") + 1)
    
    // find the newly added box
    val remoteBox =
      Get("/api/box") ~> routes ~> check {
        val boxes = responseAs[List[Box]]
        boxes.filter(_.token == token).toList(0)
      }
    
    // send image which adds outbox entry
    val imageId = 987
    Post(s"/api/box/${remoteBox.id}/sendimage", ImageId(imageId)) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outbox
    Get(s"/api/box/$token/outbox/poll") ~> routes ~> check {
      status should be(OK)
      
      val outboxEntry = responseAs[OutboxEntry]
      
      outboxEntry.remoteBoxId should be(remoteBox.id)
      outboxEntry.imageId should be(imageId)
      outboxEntry.sequenceNumber should be(1)
      outboxEntry.totalImageCount should be(1)
      outboxEntry.failed should be(false)
      outboxEntry.transactionId should not be(0)
    }
  }

  it should "return an image file when requesting outbox entry" in {
    // add image (image will get id 1)
    val fileName = "anon270.dcm"
    val file = new File(getClass().getResource(fileName).toURI())
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    Post("/api/dataset", mfd)
    
    // first, add a box on the poll (university) side
    val uniUrl =
      Post("/api/box/generatebaseurl", RemoteBoxName("hosp4")) ~> routes ~> check {
        status should be(OK)
        responseAs[BoxBaseUrl]
      }

    // get the token from the url
    val token = uniUrl.value.substring(uniUrl.value.lastIndexOf("/") + 1)
    
    // find the newly added box
    val remoteBox =
      Get("/api/box") ~> routes ~> check {
        val boxes = responseAs[List[Box]]
        boxes.filter(_.token == token).toList(0)
      }
      
    // send image which adds outbox entry
    val imageId = 1
    Post(s"/api/box/${remoteBox.id}/sendimage", ImageId(imageId)) ~> routes ~> check {
      status should be(NoContent)
    }
    
    // poll outbox
    val outboxEntry =
      Get(s"/api/box/$token/outbox/poll") ~> routes ~> check {
        status should be(OK)
        
        responseAs[OutboxEntry]
      }
    
    // get image
    Get(s"/api/box/$token/outbox?transactionId=${outboxEntry.transactionId}&sequenceNumber=1") ~> routes ~> check {
      status should be(OK)
      
      contentType should be (ContentTypes.`application/octet-stream`)  
      
      val dataset = DicomUtil.loadDataset(responseAs[Array[Byte]], true)
      dataset should not be (null)
    }
  }
  
  it should "remove outbox entry when done is received" in {
    // add image (image will get id 1)
    val fileName = "anon270.dcm"
    val file = new File(getClass().getResource(fileName).toURI())
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    Post("/api/dataset", mfd)
    
    // first, add a box on the poll (university) side
    val uniUrl =
      Post("/api/box/generatebaseurl", RemoteBoxName("hosp5")) ~> routes ~> check {
        status should be(OK)
        responseAs[BoxBaseUrl]
      }

    // get the token from the url
    val token = uniUrl.value.substring(uniUrl.value.lastIndexOf("/") + 1)
    
    // find the newly added box
    val remoteBox =
      Get("/api/box") ~> routes ~> check {
        val boxes = responseAs[List[Box]]
        boxes.filter(_.token == token).toList(0)
      }
      
    // send image which adds outbox entry
    val imageId = 1
    Post(s"/api/box/${remoteBox.id}/sendimage", ImageId(imageId)) ~> routes ~> check {
      status should be(NoContent)
    }
    
    // poll outbox
    val outboxEntry =
      Get(s"/api/box/$token/outbox/poll") ~> routes ~> check {
        status should be(OK)
        
        responseAs[OutboxEntry]
      }
      
    // send done
    Post(s"/api/box/$token/outbox/done", outboxEntry) ~> routes ~> check {
      status should be(NoContent)
    }
    
    // poll outbox to check that outbox is empty
    Get(s"/api/box/$token/outbox/poll") ~> routes ~> check {
      status should be(NotFound)
    }
  }
}