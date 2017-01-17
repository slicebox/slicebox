package se.nimsa.sbx.app.routing

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

class SeriesTypeRoutesTest extends {
  val dbConfig = TestUtil.createTestDb("seriestyperoutestest")
  val storage = new RuntimeStorage
} with FlatSpecLike with Matchers with RoutesTestBase {

  override def beforeEach() = await(seriesTypeDao.create())

  override def afterEach() = await(seriesTypeDao.drop())

  "Series type routes" should "return 200 OK and return list of series types" in {

    await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    GetAsUser("/api/seriestypes") ~> routes ~> check {
      status should be(OK)
      responseAs[List[SeriesType]].size should be(1)
    }
  }

  it should "return 201 created and created series type when adding new series type" in {
    val seriesType = SeriesType(-1, "s1")

    PostAsAdmin("/api/seriestypes", seriesType) ~> routes ~> check {
      status should be(Created)
      val returnedSeriesType = responseAs[SeriesType]

      returnedSeriesType.id should be > 0L
      returnedSeriesType.name should be(seriesType.name)
    }
  }

  it should "return 403 forbidden when adding new series type as non-admin user" in {
    val seriesType = SeriesType(-1, "s1")

    PostAsUser("/api/seriestypes", seriesType) ~> Route.seal(routes) ~> check {
      status should be(Forbidden)
    }
  }

  it should "return 204 no content when updating an existing series type" in {
    val addedSeriesType = await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    val updatedSeriesType = SeriesType(addedSeriesType.id, "st2")

    PutAsAdmin(s"/api/seriestypes/${addedSeriesType.id}", updatedSeriesType) ~> routes ~> check {
      status should be(NoContent)
    }

    GetAsUser("/api/seriestypes") ~> routes ~> check {
      val seriesTypes = responseAs[List[SeriesType]]
      seriesTypes.size should be(1)
      seriesTypes.head.name should be(updatedSeriesType.name)
    }
  }

  it should "return 204 no content when deleting an existing series type" in {
    val addedSeriesType = await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    DeleteAsAdmin(s"/api/seriestypes/${addedSeriesType.id}") ~> routes ~> check {
      status should be(NoContent)
    }

    GetAsUser("/api/seriestypes") ~> routes ~> check {
      responseAs[List[SeriesType]].size should be(0)
    }
  }

  it should "return 403 forbidden when deleting an existing series type as non-admin user" in {
    val addedSeriesType = await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    DeleteAsUser(s"/api/seriestypes/${addedSeriesType.id}") ~> Route.seal(routes) ~> check {
      status should be(Forbidden)
    }

    GetAsUser("/api/seriestypes") ~> routes ~> check {
      responseAs[List[SeriesType]].size should be(1)
    }
  }

  it should "return 200 OK and return list of series type rules" in {

    val addedSeriesType = await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    await(seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id)))

    GetAsUser(s"/api/seriestypes/rules?seriestypeid=${addedSeriesType.id}") ~> routes ~> check {
      status should be(OK)
      responseAs[List[SeriesTypeRule]].size should be(1)
    }
  }

  it should "return 201 created and created series type rule when adding new series type rule" in {
    val addedSeriesType = await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    val seriesTypeRule = SeriesTypeRule(-1, addedSeriesType.id)

    PostAsAdmin(s"/api/seriestypes/rules", seriesTypeRule) ~> routes ~> check {
      status should be(Created)
      val returnedSeriesTypeRule = responseAs[SeriesTypeRule]

      returnedSeriesTypeRule.id should be > 0L
      returnedSeriesTypeRule.seriesTypeId should be(addedSeriesType.id)
    }
  }

  it should "return 403 forbidden when adding new series type rule as non-admin user" in {
    val addedSeriesType = await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    val seriesTypeRule = SeriesTypeRule(-1, addedSeriesType.id)

    PostAsUser(s"/api/seriestypes/rules", seriesTypeRule) ~> Route.seal(routes) ~> check {
      status should be(Forbidden)
    }
  }

  it should "return 204 no content when deleting an existing series type rule" in {
    val addedSeriesType = await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    val addedSeriesTypeRule = await(seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id)))

    DeleteAsAdmin(s"/api/seriestypes/rules/${addedSeriesTypeRule.id}") ~> routes ~> check {
      status should be(NoContent)
    }

    GetAsUser(s"/api/seriestypes/rules?seriestypeid=${addedSeriesType.id}") ~> routes ~> check {
      responseAs[List[SeriesTypeRule]].size should be(0)
    }
  }

  it should "return 403 forbidden deleting an existing series type rule as non-admin user" in {
    val addedSeriesType = await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    val addedSeriesTypeRule = await(seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id)))

    DeleteAsUser(s"/api/seriestypes/rules/${addedSeriesTypeRule.id}") ~> Route.seal(routes) ~> check {
      status should be(Forbidden)
    }

    GetAsUser(s"/api/seriestypes/rules?seriestypeid=${addedSeriesType.id}") ~> routes ~> check {
      responseAs[List[SeriesTypeRule]].size should be(1)
    }
  }

  it should "return 200 OK and return list of series type rule attributes" in {

    val addedSeriesType = await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    val addedSeriesTypeRule = await(seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id)))

    await(seriesTypeDao.insertSeriesTypeRuleAttribute(SeriesTypeRuleAttribute(-1, addedSeriesTypeRule.id, 1, "Name", None, None, "test")))

    GetAsUser(s"/api/seriestypes/rules/${addedSeriesTypeRule.id}/attributes") ~> routes ~> check {
      status should be(OK)
      responseAs[List[SeriesTypeRuleAttribute]].size should be(1)
    }
  }

  it should "return 201 created and created series type rule attribute when adding new series type rule attribute" in {
    val addedSeriesType = await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    val addedSeriesTypeRule = await(seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id)))

    val seriesTypeRuleAttribute = SeriesTypeRuleAttribute(-1, addedSeriesTypeRule.id, 1, "Name", None, None, "test")

    PostAsAdmin(s"/api/seriestypes/rules/${addedSeriesTypeRule.id}/attributes", seriesTypeRuleAttribute) ~> routes ~> check {
      status should be(Created)
      val returnedSeriesTypeRuleAttribute = responseAs[SeriesTypeRuleAttribute]

      returnedSeriesTypeRuleAttribute.id should be > 0L
      returnedSeriesTypeRuleAttribute.seriesTypeRuleId should be(addedSeriesTypeRule.id)
      returnedSeriesTypeRuleAttribute.tag should be(seriesTypeRuleAttribute.tag)
      returnedSeriesTypeRuleAttribute.name should be(seriesTypeRuleAttribute.name)
      returnedSeriesTypeRuleAttribute.values should be(seriesTypeRuleAttribute.values)
    }
  }

  it should "return 403 forbidden when adding new series type rule attribute as non-admin user" in {
    val addedSeriesType = await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    val addedSeriesTypeRule = await(seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id)))

    val seriesTypeRuleAttribute = SeriesTypeRuleAttribute(-1, addedSeriesTypeRule.id, 1, "Name", None, None, "test")

    PostAsUser(s"/api/seriestypes/rules/${addedSeriesTypeRule.id}/attributes", seriesTypeRuleAttribute) ~> Route.seal(routes) ~> check {
      status should be(Forbidden)
    }
  }

  it should "return 204 no content when deleting an existing series type rule attribute" in {
    val addedSeriesType = await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    val addedSeriesTypeRule = await(seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id)))

    val addedSeriesTypeRuleAttribute = await(seriesTypeDao.insertSeriesTypeRuleAttribute(SeriesTypeRuleAttribute(-1, addedSeriesTypeRule.id, 1, "Name", None, None, "test")))

    DeleteAsAdmin(s"/api/seriestypes/rules/${addedSeriesTypeRule.id}/attributes/${addedSeriesTypeRuleAttribute.id}") ~> routes ~> check {
      status should be(NoContent)
    }

    GetAsUser(s"/api/seriestypes/rules/${addedSeriesTypeRule.id}/attributes") ~> routes ~> check {
      responseAs[List[SeriesTypeRuleAttribute]].size should be(0)
    }
  }

  it should "return 403 forbidden deleting an existing series type rule attribute as non-admin user" in {
    val addedSeriesType = await(seriesTypeDao.insertSeriesType(SeriesType(-1, "st1")))

    val addedSeriesTypeRule = await(seriesTypeDao.insertSeriesTypeRule(SeriesTypeRule(-1, addedSeriesType.id)))

    val addedSeriesTypeRuleAttribute = await(seriesTypeDao.insertSeriesTypeRuleAttribute(SeriesTypeRuleAttribute(-1, addedSeriesTypeRule.id, 1, "Name", None, None, "test")))

    DeleteAsUser(s"/api/seriestypes/rules/${addedSeriesTypeRule.id}/attributes/${addedSeriesTypeRuleAttribute.id}") ~> Route.seal(routes) ~> check {
      status should be(Forbidden)
    }

    GetAsUser(s"/api/seriestypes/rules/${addedSeriesTypeRule.id}/attributes") ~> routes ~> check {
      responseAs[List[SeriesTypeRuleAttribute]].size should be(1)
    }
  }

  it should "return 200 OK with the message 'idle' when asked about the current series type update status" in {
    GetAsUser("/api/seriestypes/rules/updatestatus") ~> routes ~> check {
      responseAs[String] should be("idle")
    }
  }

}