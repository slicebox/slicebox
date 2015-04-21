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
import se.vgregion.dicom.DicomHierarchy.Patient
import se.vgregion.dicom.DicomProperty.PatientName
import se.vgregion.dicom.DicomProperty.PatientID
import se.vgregion.dicom.DicomProperty.PatientBirthDate
import se.vgregion.dicom.DicomProperty.PatientSex

class BoxRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:boxroutestest;DB_CLOSE_DELAY=-1"

  "The system" should "return a success message when asked to generate a new base url" in {
    PostAsAdmin("/api/boxes/generatebaseurl", RemoteBoxName("hosp")) ~> routes ~> check {
      status should be(Created)
      responseAs[BoxBaseUrl].value.isEmpty should be(false)
    }
  }

  it should "return bad request message when asking to generate a new base url with a malformed request body" in {
    PostAsAdmin("/api/boxes/generatebaseurl", RemoteBox("name", "url")) ~> sealRoute(routes) ~> check {
      status should be(BadRequest)
    }
  }

  it should "return a success message when asked to add a remote box" in {
    PostAsAdmin("/api/boxes/addremotebox", RemoteBox("uni", "http://some.url/api/box/" + UUID.randomUUID())) ~> routes ~> check {
      status should be(Created)
      val box = responseAs[Box]
      box.sendMethod should be(BoxSendMethod.PUSH)
      box.name should be("uni")
    }
  }

  it should "return a bad request message when asked to add a remote box with a malformed base url" in {
    PostAsAdmin("/api/boxes/addremotebox", RemoteBox("uni2", "")) ~> sealRoute(routes) ~> check {
      status should be(BadRequest)
    }
    PostAsAdmin("/api/boxes/addremotebox", RemoteBox("uni2", "malformed/url")) ~> sealRoute(routes) ~> check {
      status should be(BadRequest)
    }
  }

  it should "return a list of two boxes when listing boxes" in {
    GetAsUser("/api/boxes") ~> routes ~> check {
      val boxes = responseAs[List[Box]]
      boxes.size should be(2)
    }
  }

  it should "return a no content message when asked to send patients" in {
    PostAsAdmin("/api/boxes/1/sendpatients", BoxSendData(Seq(1), Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a no content message when asked to send patients with empty patient ids list" in {
    PostAsAdmin("/api/boxes/1/sendpatients", BoxSendData(Seq.empty, Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a not found message when asked to send patients with unknown box id" in {
    PostAsAdmin("/api/boxes/999/sendpatients", BoxSendData(Seq(1), Seq.empty)) ~> sealRoute(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "return a no content message when asked to send studies" in {
    PostAsAdmin("/api/boxes/1/sendstudies", BoxSendData(Seq(1), Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a no content message when asked to send studies with empty study ids list" in {
    PostAsAdmin("/api/boxes/1/sendstudies", BoxSendData(Seq.empty, Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a not found message when asked to send studies with unknown box id" in {
    PostAsAdmin("/api/boxes/999/sendstudies", BoxSendData(Seq(1), Seq.empty)) ~> sealRoute(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "return a no content message when asked to send series" in {
    PostAsAdmin("/api/boxes/1/sendseries", BoxSendData(Seq(1), Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a no content message when asked to send series with empty series ids list" in {
    PostAsAdmin("/api/boxes/1/sendseries", BoxSendData(Seq.empty, Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a not found message when asked to send series with unknown box id" in {
    PostAsAdmin("/api/boxes/999/sendseries", BoxSendData(Seq(1), Seq.empty)) ~> sealRoute(routes) ~> check {
      status should be(NotFound)
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

  it should "return a no content message when asked to remove a box that does not exist" in {
    DeleteAsAdmin("/api/boxes/999") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "be able to receive a pushed image" in {

    // first, add a box on the poll (university) side
    val uniUrl =
      PostAsAdmin("/api/boxes/generatebaseurl", RemoteBoxName("hosp")) ~> routes ~> check {
        status should be(Created)
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
      status should be(NoContent)
    }
  }

  it should "return unauthorized when polling outbox with unvalid token" in {
    Get(s"/api/box/abc/outbox/poll") ~> sealRoute(routes) ~> check {
      status should be(Unauthorized)
    }
  }

  it should "return not found when polling empty outbox" in {
    // first, add a box on the poll (university) side
    val uniUrl =
      PostAsAdmin("/api/boxes/generatebaseurl", RemoteBoxName("hosp2")) ~> routes ~> check {
        status should be(Created)
        responseAs[BoxBaseUrl]
      }

    // get the token from the url
    val token = uniUrl.value.substring(uniUrl.value.lastIndexOf("/") + 1)

    Get(s"/api/box/$token/outbox/poll") ~> sealRoute(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "return OutboxEntry when polling non empty outbox" in {
    // first, add a box on the poll (university) side
    val uniUrl =
      PostAsAdmin("/api/boxes/generatebaseurl", RemoteBoxName("hosp3")) ~> routes ~> check {
        status should be(Created)
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

    // send series which adds outbox entry
    val seriesId = 1
    PostAsUser(s"/api/boxes/${remoteBox.id}/sendseries", BoxSendData(Seq(seriesId), Seq.empty)) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outbox
    Get(s"/api/box/$token/outbox/poll") ~> routes ~> check {
      status should be(OK)

      val outboxEntry = responseAs[OutboxEntry]

      outboxEntry.remoteBoxId should be(remoteBox.id)
      outboxEntry.sequenceNumber should be(1)
      outboxEntry.totalImageCount should be(1)
      outboxEntry.failed should be(false)
      outboxEntry.transactionId should not be (0)
    }
  }

  it should "return a non-empty result when listing outbox entries for sent files" in {
    GetAsUser("/api/outbox") ~> routes ~> check {
      status should be(OK)
      responseAs[List[OutboxEntryInfo]].length should be > 0
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
        status should be(Created)
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

    // send series which adds outbox entry
    val seriesId = 1
    PostAsUser(s"/api/boxes/${remoteBox.id}/sendseries", BoxSendData(Seq(seriesId), Seq.empty)) ~> routes ~> check {
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

      contentType should be(ContentTypes.`application/octet-stream`)

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
        status should be(Created)
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

    // send series which adds outbox entry
    val seriesId = 1
    PostAsUser(s"/api/boxes/${remoteBox.id}/sendseries", BoxSendData(Seq(seriesId), Seq.empty)) ~> routes ~> check {
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
    Get(s"/api/box/$token/outbox/poll") ~> sealRoute(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "correctly map dicom attributes according to supplied mapping when sending a series" in {
    // add image (image will get id 1)
    val fileName = "anon270.dcm"
    val file = new File(getClass().getResource(fileName).toURI())
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd)

    // first, add a box on the poll (university) side
    val uniUrl =
      PostAsAdmin("/api/boxes/generatebaseurl", RemoteBoxName("hosp6")) ~> routes ~> check {
        status should be(Created)
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
    
    // create attribute mappings
    val patient = GetAsUser(s"/api/metadata/patients") ~> routes ~> check {
      responseAs[List[Patient]].apply(0)
    }
    
    val seriesId = 1
    
    val tagValues = Seq(
      BoxSendTagValue(seriesId, PatientName.dicomTag, "TEST NAME"),
      BoxSendTagValue(seriesId, PatientID.dicomTag, "TEST ID"),
      BoxSendTagValue(seriesId, PatientBirthDate.dicomTag, "19601010"))
      
    // send series which adds outbox entry
    PostAsUser(s"/api/boxes/${remoteBox.id}/sendseries", BoxSendData(Seq(seriesId), tagValues)) ~> routes ~> check {
      status should be(NoContent)
    }

    // poll outbox
    val outboxEntry =
      Get(s"/api/box/$token/outbox/poll") ~> routes ~> check {
        status should be(OK)
        responseAs[OutboxEntry]
      }

    // get image
    val transactionId = outboxEntry.transactionId
    val sequenceNumber = outboxEntry.sequenceNumber
    val byteArray = Get(s"/api/box/$token/outbox?transactionid=$transactionId&sequencenumber=$sequenceNumber") ~> routes ~> check {
      status should be(OK)
      responseAs[Array[Byte]]
    }
    val dataset = DicomUtil.loadDataset(byteArray, false)
    dataset.getString(PatientName.dicomTag) should be ("TEST NAME") // mapped
    dataset.getString(PatientID.dicomTag) should be ("TEST ID") // mapped
    dataset.getString(PatientBirthDate.dicomTag) should be ("19601010") // mapped
    dataset.getString(PatientSex.dicomTag) should be (patient.patientSex.value) // not mapped
    
    // send done
    Post(s"/api/box/$token/outbox/done", outboxEntry) ~> routes ~> check {
      status should be(NoContent)
    }
  }
}