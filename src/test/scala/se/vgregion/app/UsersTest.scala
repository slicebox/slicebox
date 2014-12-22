package se.vgregion.app

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.json4s.native.Serialization

import spray.http.StatusCodes.BadRequest

class UsersTest extends FlatSpec with Matchers with RoutesTestBase {

  initialize()

  "The system" should "echo the new user when a new user is added" in {
    val user = ClearTextUser("name", Collaborator, "password")
    Put("/api/user", user) ~> routes ~> check {
      responseAs[String] should be(s"Added user ${user.user}")
    }
  }

  it should "echo the deleted user when a user is deleted" in {
    Delete("/api/user", "name") ~> routes ~> check {
      responseAs[String] should be(s"Deleted user name")
    }
  }

  it should "return an error when trying to delete an user that does not exist" in {
    Delete("/api/user", "noname") ~> routes ~> check {
      status should be (BadRequest)
    }
    
  }
  it should "return an error when trying to add an already present user" in {
    val user1 = ClearTextUser("name1", Collaborator, "password1")
    val user2 = ClearTextUser("name1", Administrator, "password2")
    Put("/api/user", user1) ~> routes
    Put("/api/user", user2) ~> routes ~> check {
      status should be (BadRequest)
    }
  }
  
  it should "return a list of user names when asking for all user names" in {
    val user2 = ClearTextUser("name2", Administrator, "password2")
    Put("/api/user", user2) ~> routes
    Get("/api/user/names") ~> routes ~> check {
      responseAs[String] should be (Serialization.write(List("name1", "name2")))
    }
  }
  
}