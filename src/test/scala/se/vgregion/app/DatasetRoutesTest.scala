package se.vgregion.app

import java.io.File
import spray.http.BodyPart
import spray.http.MultipartFormData
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import spray.http.ContentTypes
import se.vgregion.dicom.DicomUtil
import spray.http.StatusCodes._

class DatasetRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:datasetroutestest;DB_CLOSE_DELAY=-1"

  "The system" should "return a success message when adding a dataset" in {
    val fileName = "anon270.dcm"
    val file = new File(getClass().getResource(fileName).toURI())
    val mfd = MultipartFormData(Seq(BodyPart(file, "file")))
    Post("/api/datasets", mfd) ~> routes ~> check {
      responseAs[String] should be("Dataset received, added image with id 1")
    }
  }

  it should "allow fetching the dataset again" in {
    Get("/api/datasets/1") ~> routes ~> check {
      contentType should be (ContentTypes.`application/octet-stream`)      
      val dataset = DicomUtil.loadDataset(responseAs[Array[Byte]], true)
      dataset should not be (null)
    }
  }
  
  it should "return a BadRequest when requesting a dataset that does not exist" in {
    Get("/api/datasets/2") ~> routes ~> check {
      status should be (BadRequest)
    }
  }
  
  
}