package se.vgregion.app

import org.scalatest.Suite
import spray.testkit.ScalatestRouteTest
import scala.slick.jdbc.JdbcBackend.Database
import akka.actor.Actor

trait RoutesTestBase extends ScalatestRouteTest with RestApi { this: Suite =>

  def actorRefFactory = system

  override def db = Database.forURL("jdbc:h2:mem:storage;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

  def initialize() = Post("/api/initialize") ~> routes
  
  def addUser(name: String, password: String, role: Role) = {
    val user = ClearTextUser(name, role, password)
    Put("/api/user", user)
  }


}