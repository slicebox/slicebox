package se.nimsa.sbx.app.routing

import org.scalatest.Matchers
import org.scalatest.FlatSpec
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import scala.slick.driver.H2Driver
import spray.httpx.SprayJsonSupport._

import spray.http.StatusCodes._

class SeriesTypeRoutesTest extends FlatSpec with Matchers with RoutesTestBase {

  def dbUrl() = "jdbc:h2:mem:seriestyperoutestest;DB_CLOSE_DELAY=-1"

  val seriesTypeDao = new SeriesTypeDAO(H2Driver)

  override def beforeEach() {
    db.withSession { implicit session =>
      seriesTypeDao.create
    }
  }

  override def afterEach() {
    db.withSession { implicit session =>
      seriesTypeDao.drop
    }
  }

  "Series type routes" should "return 200 OK and return list of series types" in {

    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
    }

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

      returnedSeriesType.id should be > (0L)
      returnedSeriesType.name should be(seriesType.name)
    }
  }

  it should "return 403 forbidden when adding new series type as non-admin user" in {
    val seriesType = SeriesType(-1, "s1")

    PostAsUser("/api/seriestypes", seriesType) ~> sealRoute(routes) ~> check {
      status should be(Forbidden)
    }
  }

  it should "return 204 no content when updating an existing series type" in {
    val addedSeriesType = db.withSession { implicit session =>
      seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
    }

    val updatedSeriesType = SeriesType(addedSeriesType.id, "st2")

    PutAsAdmin(s"/api/seriestypes/${addedSeriesType.id}", updatedSeriesType) ~> routes ~> check {
      status should be(NoContent)
    }

    GetAsUser("/api/seriestypes") ~> routes ~> check {
      val seriesTypes = responseAs[List[SeriesType]]
      seriesTypes.size should be(1)
      seriesTypes(0).name should be(updatedSeriesType.name)
    }
  }

  it should "return 204 no content when deleting an existing series type" in {
    val addedSeriesType = db.withSession { implicit session =>
      seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
    }

    DeleteAsAdmin(s"/api/seriestypes/${addedSeriesType.id}") ~> routes ~> check {
      status should be(NoContent)
    }

    GetAsUser("/api/seriestypes") ~> routes ~> check {
      responseAs[List[SeriesType]].size should be(0)
    }
  }
}