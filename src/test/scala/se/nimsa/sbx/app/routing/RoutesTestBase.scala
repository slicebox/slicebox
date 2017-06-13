package se.nimsa.sbx.app.routing


import java.net.InetAddress

import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, _}
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, RemoteAddress}
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import com.typesafe.scalalogging.Logger
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}
import org.slf4j.LoggerFactory
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.user.UserProtocol.{ClearTextUser, UserRole}

import scala.concurrent.duration.DurationInt

trait RoutesTestBase extends ScalatestRouteTest with SliceboxBase with BeforeAndAfterAll with BeforeAndAfterEach {
  this: Suite =>

  val logger = Logger(LoggerFactory.getLogger("se.nimsa.sbx"))
  implicit val routeTestTimeout = RouteTestTimeout(10.seconds)

  val adminCredentials = BasicHttpCredentials(superUser, superPassword)
  val userCredentials = BasicHttpCredentials("user", "userpassword")

  def addUser(name: String, password: String, role: UserRole) = {
    val user = ClearTextUser(name, role, password)
    PostAsAdmin("/api/users", user) ~> Route.seal(routes) ~> check {
      status === OK
    }
  }

  override def beforeAll {
    addUser(userCredentials.username, userCredentials.password, UserRole.USER)
  }

  val testHeaders: scala.collection.immutable.Seq[HttpHeader] = scala.collection.immutable.Seq(`Remote-Address`(RemoteAddress(InetAddress.getByName("1.2.3.4"))), `User-Agent`(ProductVersion("slicebox-test")))

  def GetWithHeaders(url: String): HttpRequest = Get(url).withHeaders(testHeaders)
  def DeleteWithHeaders(url: String): HttpRequest = Delete(url).withHeaders(testHeaders)
  def PutWithHeaders[E](url: String, e: E)(implicit m: ToEntityMarshaller[E]): HttpRequest = Put(url, e).withHeaders(testHeaders)
  def PutWithHeaders(url: String): HttpRequest = Put(url).withHeaders(testHeaders)
  def PostWithHeaders[E](url: String, e: E)(implicit m: ToEntityMarshaller[E]): HttpRequest = Post(url, e).withHeaders(testHeaders)
  def PostWithHeaders(url: String): HttpRequest = Post(url).withHeaders(testHeaders)

  def GetAsAdmin(url: String): HttpRequest = GetWithHeaders(url).addCredentials(adminCredentials)
  def GetAsUser(url: String): HttpRequest = GetWithHeaders(url).addCredentials(userCredentials)
  def DeleteAsAdmin(url: String): HttpRequest = DeleteWithHeaders(url).addCredentials(adminCredentials)
  def DeleteAsUser(url: String): HttpRequest = DeleteWithHeaders(url).addCredentials(userCredentials)
  def PutAsAdmin[E](url: String, e: E)(implicit m: ToEntityMarshaller[E]): HttpRequest = PutWithHeaders(url, e).addCredentials(adminCredentials)
  def PutAsUser[E](url: String, e: E)(implicit m: ToEntityMarshaller[E]): HttpRequest = PutWithHeaders(url, e).addCredentials(userCredentials)
  def PutAsAdmin(url: String): HttpRequest = PutWithHeaders(url).addCredentials(adminCredentials)
  def PutAsUser(url: String): HttpRequest = PutWithHeaders(url).addCredentials(userCredentials)
  def PostAsAdmin[E](url: String, e: E)(implicit m: ToEntityMarshaller[E]): HttpRequest = PostWithHeaders(url, e).addCredentials(adminCredentials)
  def PostAsUser[E](url: String, e: E)(implicit m: ToEntityMarshaller[E]): HttpRequest = PostWithHeaders(url, e).addCredentials(userCredentials)
  def PostAsAdmin(url: String): HttpRequest = PostWithHeaders(url).addCredentials(adminCredentials)
  def PostAsUser(url: String): HttpRequest = PostWithHeaders(url).addCredentials(userCredentials)

}