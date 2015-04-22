package se.nimsa.sbx.app

import java.io.File
import spray.http.BodyPart
import spray.http.MultipartFormData
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import spray.http.ContentTypes
import se.nimsa.sbx.dicom.DicomUtil
import spray.http.StatusCodes._
import se.nimsa.sbx.dicom.DicomHierarchy._
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.httpx.unmarshalling.BasicUnmarshallers.ByteArrayUnmarshaller
import java.nio.file.Path
import java.nio.file.Paths
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import spray.http.HttpData

class ImageRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:datasetroutestest;DB_CLOSE_DELAY=-1"

  "Image routes" should "return a success message when adding an image" in {
    val fileName = "anon270.dcm"
    val file = new File(getClass().getResource(fileName).toURI())
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd) ~> routes ~> check {
      status should be (Created)
      val image = responseAs[Image]
      image.id should be(1)
    }
  }

  it should "allow fetching the image again" in {
    GetAsUser("/api/images/1") ~> routes ~> check {
      contentType should be (ContentTypes.`application/octet-stream`)      
      val dataset = DicomUtil.loadDataset(responseAs[Array[Byte]], true)
      dataset should not be (null)
    }
  }
  
  it should "return a BadRequest when requesting an image that does not exist" in {
    GetAsUser("/api/images/2") ~> routes ~> check {
      status should be (BadRequest)
    }
  }
  
  it should "return a BadRequest when adding an invalid image" in {
    val file = new File("some file that does not exist")
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    PostAsUser("/api/images", mfd) ~> routes ~> check {
      status should be (BadRequest)
    }    
  }

  it should "return a BadRequest when adding a file leads to an ill-defined DICOM hierarchy" in {
    // file already added once. Change some DICOM attributes in order to create an invalid hierarchy
    val fileName = "anon270.dcm"
    val dataset = DicomUtil.loadDataset(Paths.get(getClass().getResource(fileName).toURI()), true)
    dataset.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4") // leads to a new db study
    dataset.setString(Tag.SOPInstanceUID, VR.UI, "5.6.7.8") // leads to a new db image
    val bytes = DicomUtil.toByteArray(dataset)
    PostAsUser("/api/images", HttpData(bytes)) ~> routes ~> check {
      status should be (BadRequest)
    }
  }
}