package se.vgregion.app

import org.scalatest.FlatSpec
import org.scalatest.Matchers

class AppRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  initialize()

  "The system" should "not handle requests to the root API path" in {
    Get("/api/") ~> routes ~> check {
      handled should be (false)
    }
  }

}