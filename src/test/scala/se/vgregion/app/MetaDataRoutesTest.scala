package se.vgregion.app

import org.scalatest._
import spray.testkit.ScalatestRouteTest
import spray.http.StatusCodes._

class MetaDataRoutesTest extends FlatSpec with Matchers with ScalatestRouteTest with RestApi {
  def actorRefFactory = system // connect the DSL to the test ActorSystem

  "The service" should "return 200 OK when asking for all metadata" in {
    Get("/api/metadata/list") ~> routes ~> check {
      status should be(OK)
    }
  }

  override def setupDevelopmentEnvironment() = {
  }

}