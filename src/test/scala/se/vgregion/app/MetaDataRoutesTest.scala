package se.vgregion.app

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import spray.http.StatusCodes.OK
import spray.testkit.ScalatestRouteTest
import scala.concurrent.duration.DurationInt
import spray.httpx.SprayJsonSupport._
import se.vgregion.dicom.DicomHierarchy.Image

class MetaDataRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:metadataroutestest;DB_CLOSE_DELAY=-1"
  
  initialize()
  
  "The service" should "return 200 OK and return an empty list of images when asking for all images" in {
    Get("/api/metadata/allimages") ~> routes ~> check {
      status should be(OK)
      responseAs[List[Image]].size should be (0)
    }
  }

}