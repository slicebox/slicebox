package se.nimsa.sbx.app.routing

import scala.concurrent.Future
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.lang.BadGatewayException
import org.scalatest.{FlatSpecLike, Matchers}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import Directives._
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.TestUtil

class AppRoutesTest extends {
  val dbProps = TestUtil.createTestDb("approutestest")
  val storage = new RuntimeStorage
} with FlatSpecLike with Matchers with RoutesTestBase {

  def illegalArgumentRoute =
    path("illegalargumentexception") {
      onSuccess(Future.failed[String](new IllegalArgumentException("Oups"))) { extraction =>
        complete(extraction)
      }
    }

  def notFoundRoute =
    path("notfoundexception") {
      onSuccess(Future.failed[String](new NotFoundException("Oups"))) { extraction =>
        complete(extraction)
      }
    }

  def badGatewayRoute =
    path("badgatewayexception") {
      onSuccess(Future.failed[String](new BadGatewayException("Oups"))) { extraction =>
        complete(extraction)
      }
    }
  
  "The system" should "respond with BadRequest when a route throws an IllegalArgumentException" in {
    Get("/illegalargumentexception") ~> illegalArgumentRoute ~> check {
    	status shouldBe BadRequest
      responseAs[String] shouldBe "Oups"
    }    
  }
  
  it should "respond with NotFound when a route throws a MissingResourceException" in {
    Get("/notfoundexception") ~> notFoundRoute ~> check {
      status should be (NotFound)
      responseAs[String] should be ("Oups")
    }    
  }
  
  it should "respond with BadGateway when a route throws a BadGatewayException" in {
    Get("/badgatewayexception") ~> badGatewayRoute ~> check {
      status should be (BadGateway)
      responseAs[String] should be ("Oups")
    }    
  }
  
  it should "respond with NotFound when requesting a non-existant asset" in {
    Get("/assets/someasset") ~> Route.seal(routes) ~> check {
      status should be (NotFound)
    }
  }
 
  it should "respond with Forbidden when requesting an admin resource as a user" in {
    DeleteAsUser("/api/users/1") ~> Route.seal(routes) ~> check {
      status should be (Forbidden)
    }
  }
  
}