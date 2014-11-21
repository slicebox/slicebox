package se.vgregion.app

import org.scalatest._
import spray.testkit.ScalatestRouteTest
import spray.routing.HttpService
import spray.http.StatusCodes._
import spray.routing.HttpServiceActor

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
  
}