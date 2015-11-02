package se.nimsa.sbx.app.routing

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import spray.httpx.SprayJsonSupport._
import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.user.UserProtocol.UserRole._
import spray.http.StatusCodes._
import spray.http.HttpHeaders._
import spray.http.BasicHttpCredentials
import spray.routing.authentication.UserPass
import spray.http.HttpCookie
import java.util.UUID
import se.nimsa.sbx.user.UserDAO
import scala.slick.driver.H2Driver

class UserRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:userroutestest;DB_CLOSE_DELAY=-1"

  val user = ClearTextUser("name", ADMINISTRATOR, "password")

  val invalidCredentials = BasicHttpCredentials("john", "password")

  val userDao = new UserDAO(H2Driver)

  override def afterEach() {
    db.withSession { implicit session =>
      userDao.clear
      userDao.insert(ApiUser(-1, superUser, UserRole.SUPERUSER).withPassword(superPassword))
    }
  }

  "The system" should "return the new user when a new user is added" in {
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
    DeleteAsAdmin("/api/users/" + users.head.id) ~> sealRoute(routes) ~> check {
      status should be(BadRequest)
    }
  }

  it should "return status NoContent when trying to delete an user that does not exist" in {
    DeleteAsAdmin("/api/users/999") ~> routes ~> check {
      status should be(NoContent)
    }
  }

  it should "return a list of users when asking for all users" in {
    val addedUser1 = PostAsAdmin("/api/users", user) ~> routes ~> check {
      responseAs[ApiUser]
    }
    val addedUser2 = PostAsAdmin("/api/users", ClearTextUser("name2", ADMINISTRATOR, "password2")) ~> routes ~> check {
      responseAs[ApiUser]
    }
    GetAsAdmin("/api/users") ~> routes ~> check {
      val returnedUsers = responseAs[List[ApiUser]]
      returnedUsers.length should be(3)
    }
  }

  it should "respond with Unautorized when requesting an arbitrary URL under /api without any credentials" in {
    Get("/api/some/url") ~> sealRoute(routes) ~> check {
      status should be(Unauthorized)
    }
  }

  it should "respond with Unautorized when requesting an arbitrary URL under /api with bad credentials" in {
    Get("/api/some/url") ~> addCredentials(invalidCredentials) ~> sealRoute(routes) ~> check {
      status should be(Unauthorized)
    }
  }

  it should "respond with OK when using a bad session cookie but valid basic auth credentials" in {
    Get(s"/api/metadata/patients") ~> addHeader(Cookie(HttpCookie(sessionField, "badtoken"))) ~> addCredentials(adminCredentials) ~> routes ~> check {
      status should be(OK)
    }
    GetWithHeaders(s"/api/metadata/patients") ~> addHeader(Cookie(HttpCookie(sessionField, "badtoken"))) ~> addCredentials(adminCredentials) ~> routes ~> check {
      status should be(OK)
    }
  }

  it should "support logging in and respond with the logged in user and a cookie containing a session token" in {
    PostWithHeaders("/api/users/login", UserPass(superUser, superPassword)) ~> routes ~> check {
      status should be(NoContent)
      val cookies = headers.map { case `Set-Cookie`(x) => x }
      cookies.head.name should be(sessionField)
      UUID.fromString(cookies.head.content) should not be (null)
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
      headers.map { case `Set-Cookie`(x) => x }.head
    }
    GetWithHeaders(s"/api/users/current") ~> addHeader(Cookie(cookie)) ~> routes ~> check {
      status should be(OK)
      val info = responseAs[UserInfo]
      info.user shouldBe superUser
      info.role shouldBe UserRole.SUPERUSER
    }
  }

  it should "support logging out again such that subsequent API calls will return 401 Unauthorized" in {
    val cookie = PostWithHeaders("/api/users/login", UserPass(superUser, superPassword)) ~> routes ~> check {
      status should be(NoContent)
      headers.map { case `Set-Cookie`(x) => x }.head
    }
    GetWithHeaders(s"/api/metadata/patients") ~> addHeader(Cookie(cookie)) ~> routes ~> check {
      status should be(OK)
    }
    PostWithHeaders("/api/users/logout") ~> addHeader(Cookie(cookie)) ~> routes ~> check {
      status should be(NoContent)
    }
    GetWithHeaders(s"/api/metadata/patients") ~> addHeader(Cookie(cookie)) ~> sealRoute(routes) ~> check {
      status should be(Unauthorized)
    }
  }
  
  it should "authorize a logged in user based on token only" in {
    val cookie = PostWithHeaders("/api/users/login", UserPass(superUser, superPassword)) ~> routes ~> check {
      status should be(NoContent)
      headers.map { case `Set-Cookie`(x) => x }.head
    }
    db.withSession { implicit session =>
      userDao.userSessionsByToken(cookie.content).length should be(1)
    }
    GetWithHeaders(s"/api/metadata/patients") ~> addHeader(Cookie(cookie)) ~> routes ~> check {
      status should be(OK)
    }
  }

  it should "not authorize users with clients not disclosing their ip and/or user agent" in {
    val cookie = PostWithHeaders("/api/users/login", UserPass(superUser, superPassword)) ~> routes ~> check {
      status should be(NoContent)
      headers.map { case `Set-Cookie`(x) => x }.head
    }
    Get(s"/api/metadata/patients") ~> sealRoute(routes) ~> check {
      status should be(Unauthorized)
    }
    Get(s"/api/metadata/patients") ~> addHeader(Cookie(cookie)) ~> sealRoute(routes) ~> check {
      status should be(Unauthorized)
    }
    Get(s"/api/metadata/patients") ~> addHeader(Cookie(cookie)) ~> addHeader(`Remote-Address`("1.2.3.4")) ~> sealRoute(routes) ~> check {
      status should be(Unauthorized)
    }
    Get(s"/api/metadata/patients") ~> addHeader(Cookie(cookie)) ~> addHeader(`User-Agent`("spray-test")) ~> sealRoute(routes) ~> check {
      status should be(Unauthorized)
    }
    Get(s"/api/metadata/patients") ~> addHeader(`Remote-Address`("1.2.3.4")) ~> addHeader(`User-Agent`("spray-test")) ~> sealRoute(routes) ~> check {
      status should be(Unauthorized)
    }
  }

}