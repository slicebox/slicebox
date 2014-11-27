package se.vgregion.app

import org.scalatest._
import spray.testkit.ScalatestRouteTest
import spray.routing.HttpService
import spray.http.StatusCodes._
import spray.routing.HttpServiceActor
import se.vgregion.filesystem.FileSystemProtocol._
import se.vgregion.dicom.ScpProtocol._
import spray.http.ContentTypes._
import spray.httpx.marshalling.Marshaller
import spray.http.HttpEntity
import spray.http.HttpRequest
import java.nio.file.Files
import java.nio.file.FileSystems
import java.util.UUID
import java.util.UUID
import java.nio.file.Paths
import java.lang.ClassLoader
import org.scalamock.scalatest.MockFactory
import se.vgregion.dicom.MetaDataActor

class RestTest extends FlatSpec with Matchers with ScalatestRouteTest with RestApi {
  def actorRefFactory = system // connect the DSL to the test ActorSystem

  "The service" should "return 200 OK when asking for all metadata" in {
    Get("/api/metadata/list") ~> routes ~> check {
      status should be(OK)
    }
  }

  it should "return a 404 NotFound error for requests to the API root path" in {
    Get("api") ~> sealRoute(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "return a monitoring message when asked to monitor a new directory" in {
    val tempDir = Files.createTempDirectory("akka-dcm-temp-dir-")
    val tempDirName = tempDir.toString().replace("\\", "/")
    val monitorDir = MonitorDir(tempDirName)

    implicit val monitorDirMarshaller = Marshaller.of[MonitorDir](`application/json`) {
      (value, ct, ctx) => ctx.marshalTo(HttpEntity(ct, s"""{ "directory": "${monitorDir.directory}" }"""))
    }

    Put("/api/monitordirectory", monitorDir) ~> routes ~> check {
      responseAs[String] should be(s"Now monitoring directory ${monitorDir.directory}")
    }

  }

  it should "return an empty list of images when monitoring an empty directory and return one image after a file has been copied to that directory" in {
    val tempDir = Files.createTempDirectory("akka-dcm-temp-dir-")
    val tempDirName = tempDir.toString().replace("\\", "/")
    val monitorDir = MonitorDir(tempDirName)

    implicit val monitorDirMarshaller = Marshaller.of[MonitorDir](`application/json`) {
      (value, ct, ctx) => ctx.marshalTo(HttpEntity(ct, s"""{ "directory": "${monitorDir.directory}" }"""))
    }

    Put("/api/monitordirectory", monitorDir) ~> routes ~> check {
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

  it should "return a success message when asked to start a new SCP and it should be possible to remove the SCP again" in {
    val tempDir = Files.createTempDirectory("akka-dcm-temp-dir-")
    val tempDirName = tempDir.toString().replace("\\", "/")

    val scpData = ScpData("TestName", "TestAeTitle", 13579, tempDirName)

    implicit val addScpMarshaller = Marshaller.of[ScpData](`application/json`) {
      (value, ct, ctx) =>
        ctx.marshalTo(HttpEntity(ct, s"""{ 
        "name": "${scpData.name}",
        "aeTitle": "${scpData.aeTitle}",
        "port": ${scpData.port},
        "directory": "${scpData.directory}"
       }"""))
    }

    Put("/api/scp", scpData) ~> routes ~> check {
      responseAs[String] should be(s"Added SCP ${scpData.name}")
    }

    val deleteScp = DeleteScp("TestName")

    implicit val deleteScpMarshaller = Marshaller.of[DeleteScp](`application/json`) {
      (value, ct, ctx) =>
        ctx.marshalTo(HttpEntity(ct, s"""{ 
        "name": "${deleteScp.name}"
       }"""))
    }

    Delete("/api/scp", deleteScp) ~> routes ~> check {
      responseAs[String] should be(s"Deleted SCP ${deleteScp.name}")
    }
  }

  override def setupDevelopmentEnvironment() = {
    InitialValues.createTables(dbActor)
  }

}