package se.vgregion.app

import org.scalatest.Suite
import spray.testkit.ScalatestRouteTest
import scala.slick.jdbc.JdbcBackend.Database
import akka.actor.Actor
import spray.httpx.SprayJsonSupport._
import spray.http.StatusCodes.OK
import java.nio.file.Files
import org.scalatest.BeforeAndAfterAll
import se.vgregion.util.TestUtil

trait RoutesTestBase extends ScalatestRouteTest with RestApi  with BeforeAndAfterAll { this: Suite =>

  def actorRefFactory = system
  
  /*
   * Both test trait RouteTest and RestApi defines an implicit execution context (named executor and executionContext respectively). 
   * Make sure they point to the test one to avoid ambiguous implicits.
   */
  override def executionContext = executor
  
  def createStorageDirectory = Files.createTempDirectory("slicebox-test-storage-")
    
  def addUser(name: String, password: String, role: Role) = {
    val user = ClearTextUser(name, role, password)
    Put("/api/user", user)
  }

  override def afterAll {
    TestUtil.deleteFolder(storage)
  }

}