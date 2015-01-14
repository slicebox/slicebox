package se.vgregion.app

import java.nio.file.Files
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import spray.httpx.SprayJsonSupport._
import se.vgregion.box.BoxProtocol.BoxConfig
import se.vgregion.box.BoxProtocol.Boxes
import se.vgregion.box.BoxProtocol.BoxName

class BoxRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:boxroutestest;DB_CLOSE_DELAY=-1"
  
  val boxConfig1 = BoxConfig("addedBox", "url")
  
  "The system" should "return a success message when asked to add a box" in {
    Post("/api/box/add", boxConfig1) ~> routes ~> check {
      responseAs[String] should be("Added box " + boxConfig1.name)
    }
  }
  
  it should "return a list of one box when listing boxes" in {
    Get("/api/box/list") ~> routes ~> check {
      responseAs[List[BoxConfig]].size should be (1)
    }
  }
  
  it should "be possible to create a new box config" in {
    Post("/api/box/create", BoxName("box2")) ~> routes ~> check {
      responseAs[String] should be("Created box box2")
    }    
  }
  
  it should "be possible to remove the box again" in {
    Delete("/api/box", boxConfig1) ~> routes ~> check {
      responseAs[String] should be("Removed box " + boxConfig1.name)
    }
  }

}