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
import spray.http.HttpEntity
import spray.http.FormFile
import spray.http.HttpEntity.NonEmpty

class BoxRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:boxroutestest;DB_CLOSE_DELAY=-1"

  "The system" should "return a success message when asked to generate a new base url" in {
    PostAsAdmin("/api/boxes/generatebaseurl", RemoteBoxName("hosp")) ~> routes ~> check {
      status should be(OK)
      responseAs[BoxBaseUrl].value.isEmpty should be(false)
    }
  }

  it should "return a success message when asked to add a remote box" in {
    PostAsAdmin("/api/boxes/addremotebox", RemoteBox("uni", "http://some.url/api/box/" + UUID.randomUUID())) ~> routes ~> check {
      status should be(OK)
      val box = responseAs[Box]
      box.sendMethod should be(BoxSendMethod.PUSH)
      box.name should be("uni")
    }
  }

  it should "return a bad request message when asked to add a remote box with a malformed base url" in {
    PostAsAdmin("/api/boxes/addremotebox", RemoteBox("uni2", "")) ~> routes ~> check {
      status should be(BadRequest)
    }
    PostAsAdmin("/api/boxes/addremotebox", RemoteBox("uni2", "malformed/url")) ~> routes ~> check {
      status should be(BadRequest)
    }
  }

  it should "return a list of two boxes when listing boxes" in {
    GetAsUser("/api/boxes") ~> routes ~> check {
      val boxes = responseAs[List[Box]]
      boxes.size should be(2)
    }
  }

  it should "support removing a box" in {
    DeleteAsAdmin("/api/boxes/1") ~> routes ~> check {
      status should be(NoContent)
    }
    DeleteAsAdmin("/api/boxes/2") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "be able to receive a pushed image" in {

    // first, add a box on the poll (university) side
    val uniUrl =
      PostAsAdmin("/api/boxes/generatebaseurl", RemoteBoxName("hosp")) ~> routes ~> check {
        status should be(OK)
        responseAs[BoxBaseUrl]
      }

    // get the token from the url
    val token = uniUrl.value.substring(uniUrl.value.lastIndexOf("/") + 1)

    // then, push an image from the hospital to the uni box we just set up
    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(getClass().getResource(fileName).toURI())
    val bytes = DicomUtil.toAnonymizedByteArray(dcmPath)

    val testTransactionId = abs(UUID.randomUUID().getMostSignificantBits())
    val sequenceNumber = 1L
    val totalImageCount = 1L
    
    Post(s"/api/box/$token/image?transactionid=$testTransactionId&sequencenumber=$sequenceNumber&totalimagecount=$totalImageCount", HttpData(bytes)) ~> routes ~> check {
      println(responseAs[String])
      status should be(NoContent)
    }
  }
  
  it should "return not found when polling empty outbox" in {
    // first, add a box on the poll (university) side
    val uniUrl =
      PostAsAdmin("/api/boxes/generatebaseurl", RemoteBoxName("hosp2")) ~> routes ~> check {
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
      PostAsAdmin("/api/boxes/generatebaseurl", RemoteBoxName("hosp3")) ~> routes ~> check {
        status should be(OK)
        responseAs[BoxBaseUrl]
      }

    // get the token from the url
    val token = uniUrl.value.substring(uniUrl.value.lastIndexOf("/") + 1)
    
    // find the newly added box
    val remoteBox =
      GetAsUser("/api/boxes") ~> routes ~> check {
        val boxes = responseAs[List[Box]]
        boxes.filter(_.token == token).toList(0)
      }
    
    // send image which adds outbox entry
    val imageId = 987
    PostAsUser(s"/api/boxes/${remoteBox.id}/sendimages", Array(imageId)) ~> routes ~> check {
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
    PostAsUser("/api/images", mfd)
    
    // first, add a box on the poll (university) side
    val uniUrl =
      PostAsAdmin("/api/boxes/generatebaseurl", RemoteBoxName("hosp4")) ~> routes ~> check {
        status should be(OK)
        responseAs[BoxBaseUrl]
      }

    // get the token from the url
    val token = uniUrl.value.substring(uniUrl.value.lastIndexOf("/") + 1)
    
    // find the newly added box
    val remoteBox =
      GetAsUser("/api/boxes") ~> routes ~> check {
        val boxes = responseAs[List[Box]]
        boxes.filter(_.token == token).toList(0)
      }
      
    // send image which adds outbox entry
    val imageId = 1
    PostAsUser(s"/api/boxes/${remoteBox.id}/sendimages", Array(imageId)) ~> routes ~> check {
      status should be(NoContent)
    }
    
    // poll outbox
    val outboxEntry =
      Get(s"/api/box/$token/outbox/poll") ~> routes ~> check {
        status should be(OK)
        
        responseAs[OutboxEntry]
      }
    
    // get image
    Get(s"/api/box/$token/outbox?transactionid=${outboxEntry.transactionId}&sequencenumber=1") ~> routes ~> check {
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
    PostAsUser("/api/images", mfd)
    
    // first, add a box on the poll (university) side
    val uniUrl =
      PostAsAdmin("/api/boxes/generatebaseurl", RemoteBoxName("hosp5")) ~> routes ~> check {
        status should be(OK)
        responseAs[BoxBaseUrl]
      }

    // get the token from the url
    val token = uniUrl.value.substring(uniUrl.value.lastIndexOf("/") + 1)
    
    // find the newly added box
    val remoteBox =
      GetAsUser("/api/boxes") ~> routes ~> check {
        val boxes = responseAs[List[Box]]
        boxes.filter(_.token == token).toList(0)
      }
      
    // send image which adds outbox entry
    val imageId = 1
    PostAsUser(s"/api/boxes/${remoteBox.id}/sendimages", Array(imageId)) ~> routes ~> check {
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