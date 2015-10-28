package se.nimsa.sbx.app.routing

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import spray.httpx.SprayJsonSupport._
import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.user.UserProtocol.UserRole._

import spray.http.StatusCodes._
import spray.http.BasicHttpCredentials

class UserRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:userroutestest;DB_CLOSE_DELAY=-1"
  
  val user = ClearTextUser("name", ADMINISTRATOR, "password")
  var responseUser: ApiUser = null
  
  val invalidCredentials = BasicHttpCredentials("john", "password")

  "The system" should "return the new user when a new user is added" in {
    PostAsAdmin("/api/users", user) ~> routes ~> check {
      responseUser = responseAs[ApiUser]
      responseUser.user should be(user.user)
    }
  }

  it should "return the new user when adding an already added user (idempotence)" in {
    PostAsAdmin("/api/users", user) ~> routes ~> check {
      val responseUser = responseAs[ApiUser]
      responseUser.user should be(user.user)
    }    
  }
  
  it should "return status NoContent when a user is deleted" in {
    DeleteAsAdmin("/api/users/" + responseUser.id) ~> routes ~> check {
      status should be (NoContent)
    }
  }

  it should "return status NoContent when trying to delete an user that does not exist" in {
    DeleteAsAdmin("/api/users/999") ~> routes ~> check {
      status should be (NoContent)
    }
    
  }
  
  it should "return a list of users when asking for all users" in {
    val user2 = ClearTextUser("name2", ADMINISTRATOR, "password2")
    PostAsAdmin("/api/users", user2) ~> routes
    GetAsUser("/api/users") ~> routes ~> check {
      val returnedUsers = responseAs[List[ApiUser]]
      returnedUsers.length should be(2)
    }
  }
  
  it should "respond with Unautorized when requesting an arbitrary URL under /api without any credentials" in {
    Get("/api/some/url") ~> sealRoute(routes) ~> check {
      status should be (Unauthorized)
    }   
  }

  it should "respond with Unautorized when requesting an arbitrary URL under /api with bad credentials" in {
    Get("/api/some/url") ~> addCredentials(invalidCredentials) ~> sealRoute(routes) ~> check {
      status should be (Unauthorized)
    }   
  }
  
  it should "respond with OK when using no auth token and valid credentials" in {
    Get(s"/api/metadata/patients") ~> addCredentials(userCredentials) ~> routes ~> check {
      status should be (OK)
    }
  }
  
}