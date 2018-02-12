package se.nimsa.sbx.app.routing

import java.net.InetAddress
import java.util.UUID

import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.{ProductVersion, _}
import akka.http.scaladsl.server._
import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.user.UserProtocol.UserRole._
import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

class UserRoutesTest extends {
  val dbConfig = TestUtil.createTestDb("userroutestest")
  val storage = new RuntimeStorage
} with FlatSpecLike with Matchers with RoutesTestBase {

  val user = ClearTextUser("name", ADMINISTRATOR, "password")

  val invalidCredentials = BasicHttpCredentials("john", "password")

  override def afterEach() {
    await(userDao.clear())
    await(userDao.insert(ApiUser(-1, superUser, UserRole.SUPERUSER).withPassword(superPassword)))
  }

  "User routes" should "return the new user when a new user is added" in {
    PostAsAdmin("/api/users", user) ~> routes ~> check {
      responseAs[ApiUser].user should be(user.user)
    }
  }

  it should "return the new user when adding an already added user (idempotence)" in {
    PostAsAdmin("/api/users", user) ~> routes ~> check {
      responseAs[ApiUser].user should be(user.user)
    }
    PostAsAdmin("/api/users", user) ~> routes ~> check {
      responseAs[ApiUser].user should be(user.user)
    }
  }

  it should "return status NoContent when a user is deleted" in {
    val addedUser = PostAsAdmin("/api/users", user) ~> routes ~> check {
      responseAs[ApiUser]
    }
    DeleteAsAdmin("/api/users/" + addedUser.id) ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return BadRequest when trying to delete the super user" in {
    val users = GetAsAdmin("/api/users") ~> routes ~> check {
      responseAs[List[ApiUser]]
    }
    DeleteAsAdmin("/api/users/" + users.head.id) ~> Route.seal(routes) ~> check {
      status should be(BadRequest)
    }
  }

  it should "return status NoContent when trying to delete an user that does not exist" in {
    DeleteAsAdmin("/api/users/999") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a list of users when asking for all users" in {
    PostAsAdmin("/api/users", user) ~> routes ~> check {
      responseAs[ApiUser]
    }
    PostAsAdmin("/api/users", ClearTextUser("name2", ADMINISTRATOR, "password2")) ~> routes ~> check {
      responseAs[ApiUser]
    }
    GetAsAdmin("/api/users") ~> routes ~> check {
      val returnedUsers = responseAs[List[ApiUser]]
      returnedUsers.length should be(3)
    }
  }

  it should "respond with Unautorized when requesting an arbitrary URL under /api without any credentials" in {
    Get("/api/some/url") ~> Route.seal(routes) ~> check {
      status should be(Unauthorized)
    }
  }

  it should "respond with Unautorized when requesting an arbitrary URL under /api with bad credentials" in {
    Get("/api/some/url").addCredentials(invalidCredentials) ~> Route.seal(routes) ~> check {
      status should be(Unauthorized)
    }
  }

  it should "respond with OK when using a bad session cookie but valid basic auth credentials" in {
    Get(s"/api/metadata/patients").withHeaders(Cookie(sessionField, "badtoken")).addCredentials(adminCredentials) ~> routes ~> check {
      status should be(OK)
    }
    GetWithHeaders(s"/api/metadata/patients").withHeaders(Cookie(sessionField, "badtoken")).addCredentials(adminCredentials) ~> routes ~> check {
      status should be(OK)
    }
  }

  it should "support logging in and respond with the logged in user and a cookie containing a session token" in {
    PostWithHeaders("/api/users/login", UserPass(superUser, superPassword)) ~> routes ~> check {
      status should be(NoContent)
      val cookies = headers.map { case `Set-Cookie`(x) => x }
      cookies.head.name should be(sessionField)
      UUID.fromString(cookies.head.value) should not be null
    }
  }

  it should "return 401 Unauthorized when logging in with invalid credentials" in {
    PostWithHeaders("/api/users/login", UserPass(superUser, "incorrect password")) ~> routes ~> check {
      status should be(Unauthorized)
      val cookies = headers.map { case `Set-Cookie`(x) => x }
      cookies shouldBe empty
    }
  }

  it should "provide information on the currently logged in user when request is with auth cookie" in {
    val cookie = PostWithHeaders("/api/users/login", UserPass(superUser, superPassword)) ~> routes ~> check {
      status should be(NoContent)
      val c = headers.map { case `Set-Cookie`(x) => x }.head
      c.name -> c.value
    }
    GetWithHeaders(s"/api/users/current").addHeader(Cookie(cookie)) ~> routes ~> check {
      status should be(OK)
      val info = responseAs[UserInfo]
      info.user shouldBe superUser
      info.role shouldBe UserRole.SUPERUSER
    }
  }

  it should "return 404 NotFound when asking for information on the currently logged in user with invalid headers" in {
    GetWithHeaders(s"/api/users/current") ~> Route.seal(routes) ~> check {
      status should be(NotFound)
    }
  }

  it should "support logging out again such that subsequent API calls will return 401 Unauthorized" in {
    val cookie = PostWithHeaders("/api/users/login", UserPass(superUser, superPassword)) ~> routes ~> check {
      status should be(NoContent)
      val c = headers.map { case `Set-Cookie`(x) => x }.head
      c.name -> c.value
    }
    GetWithHeaders(s"/api/metadata/patients").addHeader(Cookie(cookie)) ~> routes ~> check {
      status should be(OK)
    }
    PostWithHeaders("/api/users/logout").addHeader(Cookie(cookie)) ~> routes ~> check {
      status should be(NoContent)
    }
    GetWithHeaders(s"/api/metadata/patients").addHeader(Cookie(cookie)) ~> Route.seal(routes) ~> check {
      status should be(Unauthorized)
    }
  }

  it should "authorize a logged in user based on token only" in {
    val cookie = PostWithHeaders("/api/users/login", UserPass(superUser, superPassword)) ~> routes ~> check {
      status should be(NoContent)
      val c = headers.map { case `Set-Cookie`(x) => x }.head
      c.name -> c.value
    }
    await(userDao.userSessionsByToken(cookie._2)).length should be(1)
    GetWithHeaders(s"/api/metadata/patients").addHeader(Cookie(cookie)) ~> routes ~> check {
      status should be(OK)
    }
  }

  it should "not authorize users with clients not disclosing their ip and/or user agent" in {
    val cookie = PostWithHeaders("/api/users/login", UserPass(superUser, superPassword)) ~> routes ~> check {
      status should be(NoContent)
      val c = headers.map { case `Set-Cookie`(x) => x }.head
      c.name -> c.value
    }
    Get(s"/api/metadata/patients") ~> Route.seal(routes) ~> check {
      status should be(Unauthorized)
    }
    Get(s"/api/metadata/patients").withHeaders(Cookie(cookie)) ~> Route.seal(routes) ~> check {
      status should be(Unauthorized)
    }
    Get(s"/api/metadata/patients").withHeaders(Cookie(cookie), `Remote-Address`(RemoteAddress(InetAddress.getByName("1.2.3.4")))) ~> Route.seal(routes) ~> check {
      status should be(Unauthorized)
    }
    Get(s"/api/metadata/patients").withHeaders(Cookie(cookie), `User-Agent`(ProductVersion("slicebox-test"))) ~> Route.seal(routes) ~> check {
      status should be(Unauthorized)
    }
    Get(s"/api/metadata/patients").withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("1.2.3.4"))), `User-Agent`(ProductVersion("slicebox-test"))) ~> Route.seal(routes) ~> check {
      status should be(Unauthorized)
    }
  }

}