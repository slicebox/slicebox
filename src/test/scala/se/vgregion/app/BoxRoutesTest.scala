package se.vgregion.app

import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller

import org.scalatest.FlatSpec
import org.scalatest.Matchers

import se.vgregion.box.BoxProtocol._

class BoxRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:boxroutestest;DB_CLOSE_DELAY=-1"

//  val client1 = BoxClientConfig(-1, "client1", "url")
//
//  "The system" should "return a success message when asked to create a server" in {
//    Post("/api/box/server", CreateBoxServer("server1")) ~> routes ~> check {
//      responseAs[String] should be("Created box server server1")
//    }
//  }
//
//  it should "return a success message when asked to add a client" in {
//    Post("/api/box/client", client1) ~> routes ~> check {
//      responseAs[String] should be("Added box client " + client1.name)
//    }
//  }
//
//  it should "return a list of one server when listing servers" in {
//    Get("/api/box/server/list") ~> routes ~> check {
//      val servers = responseAs[List[BoxServerConfig]]
//      servers.size should be(1)
//    }
//  }
//
//  it should "return a list of one client when listing clients" in {
//    Get("/api/box/client/list") ~> routes ~> check {
//      responseAs[List[BoxClientConfig]].size should be(1)
//    }
//  }
//
//  it should "support removing a server" in {
//    Delete("/api/box/server/1") ~> routes ~> check {
//      responseAs[String] should be("Removed box server with id 1")
//    }
//  }
//  
//  it should "support removing a client" in {
//    Delete("/api/box/client/1") ~> routes ~> check {
//      responseAs[String] should be("Removed box client with id 1")
//    }
//  }


}