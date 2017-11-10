package se.nimsa.sbx.app.routing

import akka.http.scaladsl.model.StatusCodes._
import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.sbx.app.GeneralProtocol.{Destination, DestinationType, Source, SourceType}
import se.nimsa.sbx.forwarding.ForwardingProtocol.ForwardingRule
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

class ForwardingRoutesTest extends {
  val dbConfig = TestUtil.createTestDb("forwardingroutestest")
  val storage = new RuntimeStorage
} with FlatSpecLike with Matchers with RoutesTestBase {

  override def afterEach() = await(forwardingDao.clear())

  val rule = ForwardingRule(-1, Source(SourceType.BOX, "remote box", 12), Destination(DestinationType.SCU, "my PACS", 17), keepImages = true)

  "Forwarding routes" should "return 201 Created and the created rule when creating a new forwarding rule" in {
    PostAsAdmin("/api/forwarding/rules", rule) ~> routes ~> check {
      status shouldBe Created
      val createdRule = responseAs[ForwardingRule]
      createdRule should not be null
      createdRule.id.toInt should be > 0
    }
  }

  it should "return 201 Created when adding an already added rule" in {
    PostAsAdmin("/api/forwarding/rules", rule) ~> routes ~> check {
      status shouldBe Created
    }
    PostAsAdmin("/api/forwarding/rules", rule) ~> routes ~> check {
      status shouldBe Created
    }
    GetAsUser("/api/forwarding/rules") ~> routes ~> check {
      responseAs[List[ForwardingRule]] should have length 1
    }
  }

  it should "return 201 Created when adding an already added rule but with different keepImages setting" in {
    PostAsAdmin("/api/forwarding/rules", rule) ~> routes ~> check {
      status shouldBe Created
    }
    PostAsAdmin("/api/forwarding/rules", rule.copy(keepImages = !rule.keepImages)) ~> routes ~> check {
      status shouldBe Created
    }
    GetAsUser("/api/forwarding/rules") ~> routes ~> check {
      responseAs[List[ForwardingRule]] should have length 1
    }
  }

  it should "return 200 and a list or forwarding rule when listing rules" in {
    val addedRule = PostAsAdmin("/api/forwarding/rules", rule) ~> routes ~> check {
      responseAs[ForwardingRule]
    }
    GetAsUser("/api/forwarding/rules") ~> routes ~> check {
      status shouldBe OK
      val rules = responseAs[List[ForwardingRule]]
      rules should not be empty
      rules.length shouldBe 1
      rules.head shouldBe addedRule
    }
  }

  it should "return 204 No Content and remove the referenced rule when deleting a rule" in {
    val addedRule1 = PostAsAdmin("/api/forwarding/rules", rule) ~> routes ~> check {
      responseAs[ForwardingRule]
    }
    val addedRule2 = PostAsAdmin("/api/forwarding/rules", rule.copy(source = Source(SourceType.USER, "admin", 1))) ~> routes ~> check {
      responseAs[ForwardingRule]
    }
    DeleteAsAdmin(s"/api/forwarding/rules/${addedRule1.id}") ~> routes ~> check {
      status shouldBe NoContent
    }
    val rules = GetAsUser("/api/forwarding/rules") ~> routes ~> check {
      responseAs[List[ForwardingRule]]
    }
    rules.length shouldBe 1
    rules.head shouldBe addedRule2
  }

}
