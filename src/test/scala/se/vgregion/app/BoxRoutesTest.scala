package se.vgregion.app

import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.NoContent
import se.vgregion.box.BoxProtocol._
import java.util.UUID

class BoxRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:boxroutestest;DB_CLOSE_DELAY=-1"

  "The system" should "return a success message when asked to generate a new base url" in {
    Post("/api/box/generatebaseurl", RemoteBoxName("hosp")) ~> routes ~> check {
      status should be (OK)
      responseAs[BoxBaseUrl].value.isEmpty should be (false)
    }
  }

  it should "return a success message when asked to add a remote box" in {
    Post("/api/box/addremotebox", RemoteBox("uni", "http://uni.edu/box/" + UUID.randomUUID())) ~> routes ~> check {
      status should be (OK)
      val box = responseAs[Box] 
      box.sendMethod should be (BoxSendMethod.PUSH)
      box.name should be ("uni")
    }
  }

  it should "return a bad request message when asked to add a remote box with a malformed base url" in {
    Post("/api/box/addremotebox", RemoteBox("uni2", "")) ~> routes ~> check {
      status should be (BadRequest)
    }
    Post("/api/box/addremotebox", RemoteBox("uni2", "malformed/url")) ~> routes ~> check {
      status should be (BadRequest)
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
      status should be (NoContent)
    }
  }

}