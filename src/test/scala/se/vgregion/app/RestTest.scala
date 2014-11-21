package se.vgregion.app

import org.scalatest._
import spray.testkit.ScalatestRouteTest
import spray.routing.HttpService
import spray.http.StatusCodes._
import spray.routing.HttpServiceActor
import se.vgregion.filesystem.FileSystemProtocol._
import spray.http.ContentTypes._
import spray.httpx.marshalling.Marshaller
import spray.http.HttpEntity
import spray.http.HttpRequest
import java.nio.file.Files
import java.nio.file.FileSystems

class RestTest extends FlatSpec with Matchers with ScalatestRouteTest with RestApi {
  def actorRefFactory = system // connect the DSL to the test ActorSystem

  "The service" should "return 200 OK when asking for all metadata" in {
    Get("/metadata/list") ~> routes ~> check {
      status should be(OK)
    }
  }

  it should "return a 404 NotFound error for requests to the root path" in {
    Get() ~> sealRoute(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "return a monitoring message when asked to monitor a new directory" in {
    val tempDir = Files.createTempDirectory("akka-dcm-temp-dir-").toString().replace("\\", "/")
    val monitorDir = MonitorDir(tempDir)

    implicit val monitorDirMarshaller = Marshaller.of[MonitorDir](`application/json`) {
      (value, ct, ctx) => ctx.marshalTo(HttpEntity(ct, s"""{ "directory": "${monitorDir.directory}" }"""))
    }
    
    Put("/monitordirectory", monitorDir) ~> routes ~> check {
      responseAs[String] should be(s"Now monitoring directory ${monitorDir.directory}")
    }
  }

}