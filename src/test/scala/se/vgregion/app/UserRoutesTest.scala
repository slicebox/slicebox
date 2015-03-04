package se.vgregion.app

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import spray.httpx.SprayJsonSupport._
import UserRepositoryDbProtocol._
import UserRepositoryDbProtocol.UserRole._

import spray.http.StatusCodes._

class UserRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:userroutestest;DB_CLOSE_DELAY=-1"
  
  "The system" should "return the new user when a new user is added" in {
    val user = ClearTextUser("name", ADMINISTRATOR, "password")
    Post("/api/users", user) ~> routes ~> check {
      val responseUser = responseAs[ApiUser]
      responseUser.user should be(user.user)
    }
  }

  it should "return status NoContent when a user is deleted" in {
    Delete("/api/users/1") ~> routes ~> check {
      status should be (NoContent)
    }
  }

  it should "return status NoContent when trying to delete an user that does not exist" in {
    Delete("/api/users/999") ~> routes ~> check {
      status should be (NoContent)
    }
    
  }
  it should "return an error when trying to add an already present user" in {
    val user1 = ClearTextUser("name1", ADMINISTRATOR, "password1")
    val user2 = ClearTextUser("name1", ADMINISTRATOR, "password2")
    Post("/api/users", user1) ~> routes
    Post("/api/users", user2) ~> routes ~> check {
      // TODO: should probably not return InternalServerError
      status should be (InternalServerError)
    }
  }
  
  it should "return a list of users when asking for all users" in {
    val user2 = ClearTextUser("name2", ADMINISTRATOR, "password2")
    Post("/api/users", user2) ~> routes
    Get("/api/users") ~> routes ~> check {
      val returnedUsers = responseAs[List[ApiUser]]
      returnedUsers.length should be(2)
    }
  }
  
}