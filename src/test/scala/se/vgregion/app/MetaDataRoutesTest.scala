package se.vgregion.app

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import spray.http.StatusCodes.OK
import spray.testkit.ScalatestRouteTest
import scala.concurrent.duration.DurationInt
import se.vgregion.dicom.DicomDispatchProtocol.Images

class MetaDataRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  initialize()
  
  "The service" should "return 200 OK and return an empty list of images when asking for all images" in {
    Get("/api/metadata/allimages") ~> routes ~> check {
      status should be(OK)
      responseAs[Images].images.size should be (0)
    }
  }

}