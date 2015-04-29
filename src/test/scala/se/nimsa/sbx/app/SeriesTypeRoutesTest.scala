package se.nimsa.sbx.app

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
  
  "Series type routs" should "return 200 OK and return list of series types" in {
    
    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesType(SeriesType(-1, "st1"))
    }
    
    GetAsUser("/api/seriestypes") ~> routes ~> check {
      status should be(OK)
      responseAs[List[SeriesType]].size should be (1)
    }
  }
}