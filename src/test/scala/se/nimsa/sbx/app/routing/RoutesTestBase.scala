package se.nimsa.sbx.app.routing


import java.net.InetAddress

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.duration.DurationInt
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Suite
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.user.UserProtocol.ClearTextUser
import se.nimsa.sbx.user.UserProtocol.UserRole
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.http.scaladsl.server._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, RemoteAddress}
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import se.nimsa.sbx.app.SliceboxServices

import scala.concurrent.ExecutionContext

trait RoutesTestBase extends ScalatestRouteTest with SliceboxServices with BeforeAndAfterAll with BeforeAndAfterEach { this: Suite =>

  val logger = Logger(LoggerFactory.getLogger("se.nimsa.sbx"))
  implicit val routeTestTimeout = RouteTestTimeout(10.seconds)

  val adminCredentials = BasicHttpCredentials(superUser, superPassword)
  val userCredentials = BasicHttpCredentials("user", "userpassword")

  def actorRefFactory = system

  def createStorageService() = new RuntimeStorage

  def addUser(name: String, password: String, role: UserRole) = {
    val user = ClearTextUser(name, role, password)
    PostAsAdmin("/api/users", user) ~> Route.seal(routes) ~> check {
      status === OK
    }
  }

  override def beforeAll {
    addUser(userCredentials.username, userCredentials.password, UserRole.USER)
  }

  val testHeaders: scala.collection.immutable.Seq[HttpHeader] = scala.collection.immutable.Seq(`Remote-Address`(RemoteAddress(InetAddress.getByName("1.2.3.4"))), `User-Agent`(ProductVersion("spray-test")))

  def GetWithHeaders(url: String): HttpRequest = Get(url).withHeaders(testHeaders)
  def DeleteWithHeaders(url: String): HttpRequest = Delete(url).withHeaders(testHeaders)
  def PutWithHeaders[E](url: String, e: E)(implicit m: ToEntityMarshaller[E], ec: ExecutionContext): HttpRequest = Put(url, e).withHeaders(testHeaders)
  def PutWithHeaders(url: String): HttpRequest = Put(url).withHeaders(testHeaders)
  def PostWithHeaders[E](url: String, e: E)(implicit m: ToEntityMarshaller[E], ec: ExecutionContext): HttpRequest = Post(url, e).withHeaders(testHeaders)
  def PostWithHeaders(url: String): HttpRequest = Post(url).withHeaders(testHeaders)
  
  def GetAsAdmin(url: String): HttpRequest = GetWithHeaders(url).addCredentials(adminCredentials)
  def GetAsUser(url: String): HttpRequest = GetWithHeaders(url).addCredentials(userCredentials)
  def DeleteAsAdmin(url: String): HttpRequest = DeleteWithHeaders(url).addCredentials(adminCredentials)
  def DeleteAsUser(url: String): HttpRequest = DeleteWithHeaders(url).addCredentials(userCredentials)
  def PutAsAdmin[E](url: String, e: E)(implicit m: ToEntityMarshaller[E], ec: ExecutionContext): HttpRequest = PutWithHeaders(url, e).addCredentials(adminCredentials)
  def PutAsUser[E](url: String, e: E)(implicit m: ToEntityMarshaller[E], ec: ExecutionContext): HttpRequest = PutWithHeaders(url, e).addCredentials(userCredentials)
  def PutAsAdmin(url: String): HttpRequest = PutWithHeaders(url).addCredentials(adminCredentials)
  def PutAsUser(url: String): HttpRequest = PutWithHeaders(url).addCredentials(userCredentials)
  def PostAsAdmin[E](url: String, e: E)(implicit m: ToEntityMarshaller[E], ec: ExecutionContext): HttpRequest = PostWithHeaders(url, e).addCredentials(adminCredentials)
  def PostAsUser[E](url: String, e: E)(implicit m: ToEntityMarshaller[E], ec: ExecutionContext): HttpRequest = PostWithHeaders(url, e).addCredentials(userCredentials)
  def PostAsAdmin(url: String): HttpRequest = PostWithHeaders(url).addCredentials(adminCredentials)
  def PostAsUser(url: String): HttpRequest = PostWithHeaders(url).addCredentials(userCredentials)

}