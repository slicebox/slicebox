package se.nimsa.sbx.app.routing

import java.nio.file.Files

import scala.concurrent.duration.DurationInt

import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Suite

import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.app.UserProtocol.ClearTextUser
import se.nimsa.sbx.app.UserProtocol.UserRole
import se.nimsa.sbx.util.TestUtil
import spray.http.BasicHttpCredentials
import spray.http.HttpRequest
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport._
import spray.httpx.marshalling.Marshaller
import spray.testkit.ScalatestRouteTest

trait RoutesTestBase extends ScalatestRouteTest with RestApi with BeforeAndAfterAll with BeforeAndAfterEach { this: Suite =>

  implicit val routeTestTimeout = RouteTestTimeout(10.seconds)

  val adminCredentials = BasicHttpCredentials(superUser, superPassword)
  val userCredentials = BasicHttpCredentials("user", "userpassword")

  def actorRefFactory = system

  /*
   * Both test trait RouteTest and RestApi defines an implicit execution context (named executor and executionContext respectively). 
   * Make sure they point to the test one to avoid ambiguous implicits.
   */
  override def executionContext = executor

  def createStorageDirectory = Files.createTempDirectory("slicebox-test-storage-")

  def addUser(name: String, password: String, role: UserRole) = {
    val user = ClearTextUser(name, role, password)
    PostAsAdmin("/api/users", user) ~> routes ~> check {
      status === (OK)
    }
  }

  override def afterAll {
    TestUtil.deleteFolder(storage)
  }

  override def beforeAll {
    addUser(userCredentials.username, userCredentials.password, UserRole.USER)
  }

  def GetAsAdmin(url: String): HttpRequest = Get(url) ~> addCredentials(adminCredentials)
  def GetAsUser(url: String): HttpRequest = Get(url) ~> addCredentials(userCredentials)
  def DeleteAsAdmin(url: String): HttpRequest = Delete(url) ~> addCredentials(adminCredentials)
  def DeleteAsUser(url: String): HttpRequest = Delete(url) ~> addCredentials(userCredentials)
  def PutAsAdmin[E: Marshaller](url: String, e: E): HttpRequest = Put(url, e) ~> addCredentials(adminCredentials)
  def PutAsUser[E: Marshaller](url: String, e: E): HttpRequest = Put(url, e) ~> addCredentials(userCredentials)
  def PutAsAdmin(url: String): HttpRequest = Put(url) ~> addCredentials(adminCredentials)
  def PutAsUser(url: String): HttpRequest = Put(url) ~> addCredentials(userCredentials)
  def PostAsAdmin[E: Marshaller](url: String, e: E): HttpRequest = Post(url, e) ~> addCredentials(adminCredentials)
  def PostAsUser[E: Marshaller](url: String, e: E): HttpRequest = Post(url, e) ~> addCredentials(userCredentials)
  def PostAsAdmin(url: String): HttpRequest = Post(url) ~> addCredentials(adminCredentials)
  def PostAsUser(url: String): HttpRequest = Post(url) ~> addCredentials(userCredentials)

}