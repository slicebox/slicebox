package se.vgregion.app

import org.scalatest._
import spray.testkit.ScalatestRouteTest
import spray.http.StatusCodes._
import se.vgregion.filesystem.DirectoryWatchProtocol._
import java.nio.file.Files
import java.nio.file.Paths
import spray.httpx.SprayJsonSupport._

class DirectoryRoutesTest extends FlatSpec with Matchers with ScalatestRouteTest with RestApi {
  def actorRefFactory = system // connect the DSL to the test ActorSystem

  "The system" should "return a monitoring message when asked to monitor a new directory" in {
    val tempDir = Files.createTempDirectory("slicebox-temp-dir-")
    val tempDirName = tempDir.toString().replace("\\", "/")
    val monitorDir = MonitorDir(tempDirName)

    Put("/api/directory", monitorDir) ~> routes ~> check {
      responseAs[String] should be(s"Now monitoring directory ${monitorDir.directory}")
    }

  }

  it should "return an empty list of images when monitoring an empty directory and return one image after a file has been copied to that directory" in {
    val tempDir = Files.createTempDirectory("slicebox-temp-dir-")
    val tempDirName = tempDir.toString().replace("\\", "/")
    val monitorDir = MonitorDir(tempDirName)

    Put("/api/directory", monitorDir) ~> routes ~> check {
      responseAs[String] should be(s"Now monitoring directory ${monitorDir.directory}")
    }

    Get("/api/metadata/list") ~> routes ~> check {
      status should be(OK)
      responseAs[String] indexOf ("[]") should be >= 0
    }

    val fileName = "anon270.dcm"
    val dcmPath = Paths.get(getClass().getResource(fileName).toURI())
    Files.copy(dcmPath, tempDir.resolve(fileName))

    // just sleep for a tiny bit and let the OS find out there was a new file in the monitored directory. It will be picked up and put
    // in the database
    Thread.sleep(500)

    Get("/api/metadata/list") ~> routes ~> check {
      val response = responseAs[String]
      status should be(OK)
      response indexOf ("[]") should be < 0
      """"series"""".r.findAllMatchIn(response).length should be(1) // rough check for one element in list
    }
  }

  override def setupDevelopmentEnvironment() = {
 }

}