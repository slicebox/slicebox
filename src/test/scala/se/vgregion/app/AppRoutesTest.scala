package se.vgregion.app

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import scala.concurrent.Future
import spray.routing._
import spray.util.SingletonException
import spray.routing.directives.OnSuccessFutureMagnet
import scala.concurrent.ExecutionContext
import spray.http.StatusCodes.BadRequest

class AppRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:approutestest;DB_CLOSE_DELAY=-1"

  def testRoute =
    path("castexception") {
      onSuccess(Future.failed[String](new IllegalArgumentException("Oups"))) { extraction =>
        complete(extraction)
      }
    }

  override def routes = testRoute ~ super.routes

  "The system" should "respond with BadRequest when a route throws an IllegalArgumentException" in {
    Get("/castexception") ~> routes ~> check {
    	status should be (BadRequest)
      responseAs[String] should be ("Illegal arguments: Oups")
    }    
  }

}