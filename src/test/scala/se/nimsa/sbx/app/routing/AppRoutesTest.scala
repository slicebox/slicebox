package se.nimsa.sbx.app.routing

import scala.concurrent.Future
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.lang.BadGatewayException

class AppRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl = "jdbc:h2:mem:approutestest;DB_CLOSE_DELAY=-1"

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
    	status should be (BadRequest)
      responseAs[String] should be ("Oups")
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
    Get("/assets/someasset") ~> sealRoute(routes) ~> check {
      status should be (NotFound)
    }
  }
 
  it should "respond with Forbidden when requesting an admin resource as a user" in {
    DeleteAsUser("/api/users/1") ~> sealRoute(routes) ~> check {
      status should be (Forbidden)
    }
  }
  
}