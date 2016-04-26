package se.nimsa.sbx.app.routing

import java.nio.file.{Paths, Path, Files}

import com.typesafe.config.{ConfigFactory, Config}
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import se.nimsa.sbx.storage.{FileStorage, StorageServiceActor}

import scala.concurrent.duration.DurationInt

import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Suite

import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.user.UserProtocol.ClearTextUser
import se.nimsa.sbx.user.UserProtocol.UserRole
import se.nimsa.sbx.util.TestUtil
import spray.http.BasicHttpCredentials
import spray.http.HttpRequest
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport._
import spray.httpx.marshalling.Marshaller
import spray.testkit.ScalatestRouteTest
import spray.http.HttpHeaders.`Remote-Address`
import spray.http.HttpHeaders.`User-Agent`

trait RoutesTestBase extends ScalatestRouteTest with SliceboxService with BeforeAndAfterAll with BeforeAndAfterEach { this: Suite =>

  val logger = Logger(LoggerFactory.getLogger("se.nimsa.sbx"))
  implicit val routeTestTimeout = RouteTestTimeout(10.seconds)

  val adminCredentials = BasicHttpCredentials(superUser, superPassword)
  val userCredentials = BasicHttpCredentials("user", "userpassword")

  def actorRefFactory = system

  /*
   * Both test trait RouteTest and RestApi defines an implicit execution context (named executor and executionContext respectively). 
   * Make sure they point to the test one to avoid ambiguous implicits.
   */
  override def executionContext = executor


  def appConfig: Config = ConfigFactory.load("test.conf")

  val unitTestPath = Paths.get(sliceboxConfig.getString("dicom-storage.file-system.path"))

  def addUser(name: String, password: String, role: UserRole) = {
    val user = ClearTextUser(name, role, password)
    PostAsAdmin("/api/users", user) ~> sealRoute(routes) ~> check {
      status === (OK)
    }
  }

  override def afterAll {
    TestUtil.deleteFolder(unitTestPath)
  }

  override def beforeAll {
    addUser(userCredentials.username, userCredentials.password, UserRole.USER)
  }

  def GetWithHeaders(url: String): HttpRequest = Get(url) ~> addHeader(`Remote-Address`("1.2.3.4")) ~> addHeader(`User-Agent`("spray-test"))
  def DeleteWithHeaders(url: String): HttpRequest = Delete(url) ~> addHeader(`Remote-Address`("1.2.3.4")) ~> addHeader(`User-Agent`("spray-test"))
  def PutWithHeaders[E: Marshaller](url: String, e: E): HttpRequest = Put(url, e) ~> addHeader(`Remote-Address`("1.2.3.4")) ~> addHeader(`User-Agent`("spray-test"))
  def PutWithHeaders(url: String): HttpRequest = Put(url) ~> addHeader(`Remote-Address`("1.2.3.4")) ~> addHeader(`User-Agent`("spray-test"))
  def PostWithHeaders[E: Marshaller](url: String, e: E): HttpRequest = Post(url, e) ~> addHeader(`Remote-Address`("1.2.3.4")) ~> addHeader(`User-Agent`("spray-test"))
  def PostWithHeaders(url: String): HttpRequest = Post(url) ~> addHeader(`Remote-Address`("1.2.3.4")) ~> addHeader(`User-Agent`("spray-test"))
  
  def GetAsAdmin(url: String): HttpRequest = GetWithHeaders(url) ~> addCredentials(adminCredentials)
  def GetAsUser(url: String): HttpRequest = GetWithHeaders(url) ~> addCredentials(userCredentials)
  def DeleteAsAdmin(url: String): HttpRequest = DeleteWithHeaders(url) ~> addCredentials(adminCredentials)
  def DeleteAsUser(url: String): HttpRequest = DeleteWithHeaders(url) ~> addCredentials(userCredentials)
  def PutAsAdmin[E: Marshaller](url: String, e: E): HttpRequest = PutWithHeaders(url, e) ~> addCredentials(adminCredentials)
  def PutAsUser[E: Marshaller](url: String, e: E): HttpRequest = PutWithHeaders(url, e) ~> addCredentials(userCredentials)
  def PutAsAdmin(url: String): HttpRequest = PutWithHeaders(url) ~> addCredentials(adminCredentials)
  def PutAsUser(url: String): HttpRequest = PutWithHeaders(url) ~> addCredentials(userCredentials)
  def PostAsAdmin[E: Marshaller](url: String, e: E): HttpRequest = PostWithHeaders(url, e) ~> addCredentials(adminCredentials)
  def PostAsUser[E: Marshaller](url: String, e: E): HttpRequest = PostWithHeaders(url, e) ~> addCredentials(userCredentials)
  def PostAsAdmin(url: String): HttpRequest = PostWithHeaders(url) ~> addCredentials(adminCredentials)
  def PostAsUser(url: String): HttpRequest = PostWithHeaders(url) ~> addCredentials(userCredentials)

}