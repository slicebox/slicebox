package se.vgregion.app

import org.scalatest.Suite
import spray.testkit.ScalatestRouteTest
import scala.slick.jdbc.JdbcBackend.Database
import akka.actor.Actor
import spray.httpx.SprayJsonSupport._
import spray.http.StatusCodes.OK

trait RoutesTestBase extends ScalatestRouteTest with RestApi { this: Suite =>

  def actorRefFactory = system

  def addUser(name: String, password: String, role: Role) = {
    val user = ClearTextUser(name, role, password)
    Put("/api/user", user)
  }

}