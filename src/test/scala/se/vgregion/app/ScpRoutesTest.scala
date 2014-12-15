package se.vgregion.app

import org.scalatest._
import spray.testkit.ScalatestRouteTest
import se.vgregion.dicom.scp.ScpProtocol._
import spray.httpx.SprayJsonSupport._
import java.nio.file.Files

class ScpRoutesTest extends FlatSpec with Matchers with ScalatestRouteTest with RestApi {
  def actorRefFactory = system // connect the DSL to the test ActorSystem

  "The system" should "return a success message when asked to start a new SCP and it should be possible to remove the SCP again" in {
    val tempDir = Files.createTempDirectory("akka-dcm-temp-dir-")
    val tempDirName = tempDir.toString().replace("\\", "/")

    // TODO
    val storage = tempDir
    
    val scpData = ScpData("TestName", "TestAeTitle", 13579)

    Put("/api/scp", scpData) ~> routes ~> check {
      responseAs[String] should be(s"Added SCP ${scpData.name}")
    }

    val deleteScp = DeleteScp("TestName")

    Delete("/api/scp", deleteScp) ~> routes ~> check {
      responseAs[String] should be(s"Deleted SCP ${deleteScp.name}")
    }
  }

  override def setupDevelopmentEnvironment() = {
  }

}