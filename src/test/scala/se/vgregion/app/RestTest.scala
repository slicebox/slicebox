package se.vgregion.app

import org.scalatest._
import spray.testkit.ScalatestRouteTest
import spray.routing.HttpService
import spray.http.StatusCodes._
import spray.routing.HttpServiceActor

class RestTest extends FlatSpec with Matchers with ScalatestRouteTest with RestApi with BeforeAndAfterAll {
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

  // tear down the SCP after tests finish or sbt will leave these threads hanging
  override def afterAll() {
    import akka.pattern.ask
    import scala.util.{ Success, Failure }
    import se.vgregion.dicom.ScpProtocol._
    
    scpCollectionActor.ask(DeleteScp("TestSCP")).onComplete {
      case Success(any) =>
        println(s"System torn down: $any")
      case Failure(reason) =>
        println(s"System not torn down: $reason")
    }(executionContext)
  }
  
}