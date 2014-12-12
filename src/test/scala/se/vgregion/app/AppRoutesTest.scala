package se.vgregion.app

import org.scalatest._
import spray.testkit.ScalatestRouteTest

class AppRoutesTest extends FlatSpec with Matchers with ScalatestRouteTest with RestApi {
  def actorRefFactory = system // connect the DSL to the test ActorSystem

  "The system" should "not handle requests to the root API path" in {
    Get("/api/") ~> routes ~> check {
      handled should be (false)
    }
  }

  override def setupDevelopmentEnvironment() = {
    InitialValues.createTables(dbActor)
  }

}