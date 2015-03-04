package se.vgregion.app

import java.nio.file.Files

import scala.concurrent.duration.DurationInt

import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.testkit.ScalatestRouteTest

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite

import se.vgregion.util.TestUtil

import UserRepositoryDbProtocol._

trait RoutesTestBase extends ScalatestRouteTest with RestApi with BeforeAndAfterAll { this: Suite =>

  implicit val routeTestTimeout = RouteTestTimeout(5.second)

  def actorRefFactory = system

  /*
   * Both test trait RouteTest and RestApi defines an implicit execution context (named executor and executionContext respectively). 
   * Make sure they point to the test one to avoid ambiguous implicits.
   */
  override def executionContext = executor

  def createStorageDirectory = Files.createTempDirectory("slicebox-test-storage-")

  def addUser(name: String, password: String, role: UserRole) = {
    val user = ClearTextUser(name, role, password)
    Put("/api/user", user)
  }

  override def afterAll {
    TestUtil.deleteFolder(storage)
  }
  
  // TODO: add test user
  // TODO: help methods for HTTP calls that adds autherization

}