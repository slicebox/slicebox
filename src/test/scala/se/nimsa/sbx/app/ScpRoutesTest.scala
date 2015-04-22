package se.nimsa.sbx.app

import java.nio.file.Files
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.nimsa.sbx.dicom.DicomProtocol._
import spray.httpx.SprayJsonSupport._
import spray.http.StatusCodes._

class ScpRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:scproutestest;DB_CLOSE_DELAY=-1"
  
  "The system" should "return a success message when asked to start a new SCP" in {
    PostAsAdmin("/api/scps", AddScp("TestName", "TestAeTitle", 13579)) ~> routes ~> check {
      val scpData = responseAs[ScpData]
      scpData.name should be("TestName")
    }
  }
  
  it should "be possible to remove the SCP again" in {
    DeleteAsAdmin("/api/scps/1") ~> routes ~> check {
      status should be(NoContent)
    }
  }

}