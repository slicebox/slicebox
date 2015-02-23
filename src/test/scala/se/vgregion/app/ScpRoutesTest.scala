package se.vgregion.app

import java.nio.file.Files
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.vgregion.dicom.DicomProtocol.RemoveScp
import se.vgregion.dicom.DicomProtocol.AddScp
import spray.httpx.SprayJsonSupport._

class ScpRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:scproutestest;DB_CLOSE_DELAY=-1"
  
  "The system" should "return a success message when asked to start a new SCP" in {
    Post("/api/scps", AddScp("TestName", "TestAeTitle", 13579)) ~> routes ~> check {
      responseAs[String] should be("Added SCP TestName")
    }
  }
  
  it should "be possible to remove the SCP again" in {
    Delete("/api/scps/1") ~> routes ~> check {
      responseAs[String] should be("Removed SCP 1")
    }
  }

}